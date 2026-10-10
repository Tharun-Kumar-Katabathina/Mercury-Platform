"""The failure scenarios. Each one injects a fault into the running container stack, observes how the platform
behaves, repairs the fault, and returns a one-line description of what actually happened. The runner then checks
the system invariants (no oversell, no stranded stock, every order final, outbox drained, no duplicate effects)."""
import threading
import time
import uuid

from lib import (Auth, Fixture, URLS, check_invariants, get_order, histogram, http, log, order_ids, place_order, run_load,
                 await_until)

SCENARIOS = []


def scenario(sid, title, mode, expected, invariants=True, deadline="12s"):
    """deadline: how long an ASYNC order waits for Inventory before recovery asks it directly. Crash-recovery
    scenarios use a long one (the service needs a minute to restart); timeout scenarios use the short one."""
    def register(fn):
        SCENARIOS.append(dict(id=sid, title=title, mode=mode, expected=expected, fn=fn, invariants=invariants, deadline=deadline))
        return fn
    return register


def background(fn):
    t = threading.Thread(target=fn, daemon=True)
    t.start()
    return t


def notifications(stack, order_ids_):
    if not order_ids_:
        return 0, 0
    ids = ",".join(f"'{o}'" for o in order_ids_)
    r = stack.sql("mercury_notification", f"select count(*), count(distinct order_id) from notification where order_id in ({ids})")[0]
    return int(r[0]), int(r[1])


def status_of(stack, oid):
    return stack.sql("mercury_order", f"select status from orders where id='{oid}'")[0][0]


def saga_of(stack, oid):
    r = stack.sql("mercury_order", f"select state, attempt_count from order_saga where order_id='{oid}'")
    return (r[0][0], int(r[0][1])) if r else (None, 0)


def cancel_reason(stack, oid):
    r = stack.sql("mercury_order", f"select payload from order_outbox where aggregate_id='{oid}' and event_type='OrderCancelled'")
    return r[0][0] if r else None


# =============================================================================== SYNC mode

@scenario("F01", "Inventory Service killed (SIGKILL) under load", "SYNC",
          "orders fail fast and bounded while it is down, nothing is corrupted, service resumes after restart")
def inventory_killed(stack, fx):
    pid = fx.new_product(60)
    results = []
    load = background(lambda: results.extend(run_load(pid, 40, workers=4, pace=0.3)))
    time.sleep(3)
    stack.kill("inventory-service")
    time.sleep(12)
    stack.start("inventory-service")
    stack.wait_healthy()
    load.join(timeout=120)
    h = histogram(results)
    failures = [r for r in results if r.status not in (201,)]
    slowest = max((r.seconds for r in failures), default=0)
    assert h.get(201, 0) > 0, f"nothing succeeded: {h}"
    assert failures, f"no request noticed the outage: {h}"
    assert slowest < 20, f"a failing request took {slowest:.1f}s: not bounded"
    assert set(h) <= {201, 409, 500, 502, 503}, f"unexpected statuses {h}"
    # The circuit breaker may still be open (10 s) because its half-open probe can land while Inventory is still booting
    # and fail; it closes again by itself. "Resumes" therefore means: within a bounded time, not in the same instant.
    healthy_at = time.time()
    after = place_order(pid)
    while after.status != 201 and time.time() - healthy_at < 25:
        time.sleep(1)
        after = place_order(pid)
    assert after.status == 201, f"no order succeeded within 25 s of Inventory being healthy: last {after.status} {after.body}"
    return f"{h}; slowest failed request {slowest:.1f}s; first new order after restart = 201 after {time.time() - healthy_at:.1f}s"


@scenario("F02", "Order Service killed (SIGKILL) mid-saga under load", "SYNC",
          "interrupted sagas are finished by recovery after restart: every order final, no stranded stock")
def order_killed(stack, fx):
    pid = fx.new_product(60)
    results = []
    load = background(lambda: results.extend(run_load(pid, 40, workers=4, pace=0.3)))
    time.sleep(3)
    stack.kill("order-service")
    time.sleep(6)
    stack.start("order-service")
    stack.wait_healthy()
    load.join(timeout=120)
    h = histogram(results)
    assert h.get(0, 0) > 0 or h.get(201, 0) < 40, f"the crash was not noticed by any client: {h}"
    return f"client view {h} (0 = connection refused/reset while Order was down)"


@scenario("F03", "Product Service killed", "SYNC",
          "orders are refused with 503 PRODUCT_SERVICE_UNAVAILABLE before anything is saved; works again after restart")
def product_killed(stack, fx):
    pid = fx.new_product(20)
    stack.kill("product-service")
    rs = [place_order(pid) for _ in range(5)]
    h = histogram(rs)
    assert set(h) == {503}, f"expected only 503, got {h}"
    assert all(isinstance(r.body, dict) and r.body.get("error") == "PRODUCT_SERVICE_UNAVAILABLE" for r in rs), rs[0].body
    rows = stack.sql("mercury_order", f"select count(*) from order_items where product_id='{pid}'")[0][0]
    assert rows == "0", f"{rows} order rows were saved for refused requests"
    stack.start("product-service")
    stack.wait_healthy()
    ok = place_order(pid)
    assert ok.status == 201, f"after restart -> {ok.status}"
    return f"5 requests -> {h}, 0 order rows saved; after restart 201"


@scenario("F04", "Notification Service killed", "SYNC",
          "ordering is unaffected; after restart every notification arrives exactly once from Kafka")
def notification_killed(stack, fx):
    pid = fx.new_product(20)
    stack.kill("notification-service")
    rs = [place_order(pid) for _ in range(5)]
    bad = [(r.status, r.body) for r in rs if r.status != 201]
    assert not bad, (f"orders must not depend on Notification, but: {histogram(rs)} {bad[:1]}; "
                     f"order-service logged: {stack.logged('order-service', pid)}")
    ids = order_ids(rs)
    time.sleep(4)
    assert notifications(stack, ids)[0] == 0, "notifications appeared while the service was down"
    stack.start("notification-service")
    stack.wait_healthy()
    got = await_until(lambda: notifications(stack, ids) == (5, 5), timeout=90)
    assert got, f"notifications after restart: {notifications(stack, ids)}"
    return "5 orders confirmed while Notification was down; after restart exactly 5 notifications (5 distinct orders)"


@scenario("F05", "Kafka unavailable (frozen)", "SYNC",
          "ordering keeps working; events wait in the outbox and are published, in full, when Kafka returns")
def kafka_unavailable(stack, fx):
    pid = fx.new_product(20)
    stack.pause("kafka")
    rs = [place_order(pid) for _ in range(6)]
    assert set(histogram(rs)) == {201}, f"orders must not depend on Kafka: {histogram(rs)}"
    ids = order_ids(rs)
    time.sleep(4)
    oids = ",".join(f"'{i}'" for i in ids)
    waiting = int(stack.sql("mercury_order", f"select count(*) from order_outbox where aggregate_id in ({oids}) and published_at is null")[0][0])
    assert waiting > 0, "nothing waited in the outbox"
    stack.unpause("kafka")
    got = await_until(lambda: notifications(stack, ids) == (6, 6), timeout=120)
    assert got, f"notifications after Kafka returned: {notifications(stack, ids)}"
    return f"6 orders = 201 during the outage; {waiting} events waited in the outbox; all 6 notifications delivered afterwards"


@scenario("F06", "Kafka restarted under load", "SYNC",
          "no event is lost; the broker comes back with its data; every notification arrives exactly once")
def kafka_restart(stack, fx):
    pid = fx.new_product(80)
    first = [place_order(pid) for _ in range(3)]
    results = []
    load = background(lambda: results.extend(run_load(pid, 40, workers=3, pace=0.4)))
    time.sleep(1.5)
    stack.restart("kafka")
    stack.wait_healthy()
    load.join(timeout=120)
    ids = order_ids(first) + order_ids(results)
    got = await_until(lambda: notifications(stack, ids) == (len(ids), len(ids)), timeout=150)
    assert got, f"notifications {notifications(stack, ids)} for {len(ids)} orders"
    assert stack.topic_size("mercury.order.events") >= len(ids), "topic data lost across the restart"
    return f"{len(ids)} orders confirmed ({histogram(first + results)}); {len(ids)} notifications, none lost, none duplicated"


@scenario("F07", "PostgreSQL stopped, then started", "SYNC",
          "services report not-ready and refuse orders cleanly while it is down, recover on their own, data intact")
def postgres_down(stack, fx):
    pid = fx.new_product(20)
    keep = place_order(pid)
    assert keep.status == 201
    stack.stop("postgres")
    rs = [place_order(pid) for _ in range(3)]
    h = histogram(rs)
    assert 201 not in h, f"an order was accepted without a database: {h}"
    ready = await_until(lambda: http("GET", f"{URLS['order']}/actuator/health/readiness", timeout=5).status != 200, timeout=40)
    assert ready, "readiness never reported the database outage"
    stack.start("postgres")
    stack.wait_healthy()
    ok = await_until(lambda: place_order(pid).status == 201, timeout=90, interval=3)
    assert ok, "ordering did not resume after PostgreSQL returned"
    return f"3 orders during the outage -> {h}; readiness went down and came back; ordering resumed; earlier order intact"


@scenario("F08", "PostgreSQL restarted under load", "SYNC",
          "in-flight orders end final (confirmed or cancelled) after the restart, with no corrupted stock")
def postgres_restart_load(stack, fx):
    pid = fx.new_product(150)
    results = []
    load = background(lambda: results.extend(run_load(pid, 120, workers=4, pace=0.25)))
    time.sleep(2)
    stack.restart("postgres")
    stack.wait_healthy()
    load.join(timeout=150)
    slowest = max((r.seconds for r in results), default=0)
    return f"client view {histogram(results)}; slowest request {slowest:.1f}s (requests wait for the database up to the pool timeout, then succeed)"


@scenario("F09", "Network partition: Inventory unreachable (no RST, connections hang)", "SYNC",
          "calls time out in bounded time; ambiguous reservations are resolved after the partition heals")
def network_partition(stack, fx):
    pid = fx.new_product(30)
    stack.partition("inventory-service")
    t0 = time.time()
    rs = [place_order(pid) for _ in range(4)]
    h = histogram(rs)
    slowest = max(r.seconds for r in rs)
    assert 201 not in h, f"an order succeeded across a partition: {h}"
    assert slowest < 25, f"a request hung for {slowest:.1f}s"
    stack.clear_delay("inventory-service")
    stack.wait_healthy()
    ok = await_until(lambda: place_order(pid).status == 201, timeout=90, interval=3)
    assert ok, "ordering did not resume after the partition healed"
    return f"4 orders during the partition -> {h}, slowest {slowest:.1f}s (bounded); resumed after healing"


@scenario("F10", "Network delay: Inventory answers after 7 s (read timeout is 5 s)", "SYNC",
          "calls time out as ambiguous; the reservation that did happen is found and released; stock conserved")
def network_delay(stack, fx):
    pid = fx.new_product(30)
    stack.delay("inventory-service", 7000)
    try:
        rs = [place_order(pid) for _ in range(3)]
        h = histogram(rs)
        assert 201 not in h, f"an order was confirmed despite the timeouts: {h}"
    finally:
        stack.clear_delay("inventory-service")
    return f"3 orders under 7 s latency -> {h}; delay removed, recovery resolved the ambiguous reservations"


# =============================================================================== ASYNC mode

@scenario("F11", "Inventory killed (SIGKILL) while consuming reservation commands", "ASYNC",
          "commands are redelivered after restart; each order is reserved exactly once", deadline="300s")
def consumer_crash(stack, fx):
    pid = fx.new_product(60)
    rs = [place_order(pid) for _ in range(12)]
    assert set(histogram(rs)) == {202}, histogram(rs)
    stack.kill("inventory-service")
    time.sleep(4)
    stack.start("inventory-service")
    stack.wait_healthy()
    ids = order_ids(rs)
    done = await_until(lambda: all(status_of(stack, i) == "CONFIRMED" for i in ids), timeout=150)
    assert done, "not every order was confirmed after the consumer restarted"
    n = stack.sql("mercury_inventory", f"select count(*) from order_reservations where order_id in ({','.join(repr(i) for i in ids)})")[0][0]
    assert n == str(len(ids)), f"{n} reservation decisions for {len(ids)} orders"
    return f"12 orders accepted (202); consumer killed and restarted; all 12 CONFIRMED, exactly 12 reservation decisions"


@scenario("F12", "Order Service killed (SIGKILL) right after accepting orders", "ASYNC",
          "the outbox still delivers every command after restart; orders complete; effect exactly once", deadline="300s")
def producer_crash(stack, fx):
    pid = fx.new_product(60)
    rs = [place_order(pid) for _ in range(12)]
    assert set(histogram(rs)) == {202}, histogram(rs)
    stack.kill("order-service")
    time.sleep(3)
    stack.start("order-service")
    stack.wait_healthy()
    ids = order_ids(rs)
    done = await_until(lambda: all(status_of(stack, i) == "CONFIRMED" for i in ids), timeout=150)
    assert done, "not every order completed after the producer restarted"
    return "12 orders accepted, Order killed before/while publishing; after restart all 12 CONFIRMED"


@scenario("F13", "Duplicate command delivered three more times", "ASYNC",
          "one reservation, one reply: the duplicates change nothing", deadline="300s")
def duplicate_message(stack, fx):
    pid = fx.new_product(20)
    r = place_order(pid, 3)
    oid = r.body["id"]
    assert await_until(lambda: status_of(stack, oid) == "CONFIRMED", timeout=90)
    before = stack.metric("inventory-service", "inventory_commands_duplicate_total") or 0
    payload = stack.sql("mercury_order", f"select payload from order_outbox where aggregate_id='{oid}' and event_type='InventoryReservationRequested'")[0][0]
    for _ in range(3):
        stack.produce("mercury.inventory.commands", oid, payload)
    after = await_until(lambda: (stack.metric("inventory-service", "inventory_commands_duplicate_total") or 0) >= before + 3, timeout=60)
    assert after, "the duplicates were not recognised"
    replies = stack.sql("mercury_inventory", f"select count(*) from inventory_outbox where aggregate_id='{oid}'")[0][0]
    assert replies == "1", f"{replies} replies written for one order"
    return "3 duplicate commands recognised and ignored (counter +3); 1 reservation, 1 reply, stock unchanged"


@scenario("F14", "Out-of-order and unknown replies", "ASYNC",
          "replies for unknown orders, repeated replies, and a rejection after confirmation are all ignored", deadline="300s")
def out_of_order(stack, fx):
    pid = fx.new_product(20)
    oid = place_order(pid, 2).body["id"]
    assert await_until(lambda: status_of(stack, oid) == "CONFIRMED", timeout=90)
    ignored0 = stack.metric("order-service", "order_inventory_events_ignored_total") or 0
    dup0 = stack.metric("order-service", "order_inventory_events_duplicate_total") or 0
    stranger = str(uuid.uuid4())
    now = "2026-10-06T12:00:00Z"
    unknown = f'{{"eventId":"{uuid.uuid4()}","eventType":"InventoryReserved","occurredAt":"{now}","orderId":"{stranger}","items":[{{"productId":"{pid}","quantity":1}}]}}'
    rejected_after = f'{{"eventId":"{uuid.uuid4()}","eventType":"InventoryRejected","occurredAt":"{now}","orderId":"{oid}","reason":"INSUFFICIENT_STOCK","productId":"{pid}"}}'
    repeated_id = str(uuid.uuid4())
    repeated = f'{{"eventId":"{repeated_id}","eventType":"InventoryRejected","occurredAt":"{now}","orderId":"{oid}","reason":"X","productId":"{pid}"}}'
    stack.produce("mercury.inventory.events", stranger, unknown)
    stack.produce("mercury.inventory.events", oid, rejected_after)
    stack.produce("mercury.inventory.events", oid, repeated)
    stack.produce("mercury.inventory.events", oid, repeated)
    ok = await_until(lambda: (stack.metric("order-service", "order_inventory_events_ignored_total") or 0) >= ignored0 + 3
                     and (stack.metric("order-service", "order_inventory_events_duplicate_total") or 0) >= dup0 + 1, timeout=60)
    assert ok, "the stray replies were not all classified as ignored/duplicate"
    assert status_of(stack, oid) == "CONFIRMED", "a stray rejection changed a confirmed order"
    return "unknown-order reply, rejection-after-confirmation and a repeated reply: all ignored, order still CONFIRMED"


@scenario("F15", "Reservation timeout, then the reservation arrives late", "ASYNC",
          "order is cancelled with RESERVATION_TIMEOUT; the late reservation is detected and released; stock conserved")
def late_reservation(stack, fx):
    pid = fx.new_product(30)
    late0 = stack.metric("order-service", "order_inventory_late_reservations_total") or 0
    stack.pause("kafka")                                   # the command cannot leave Order
    rs = [place_order(pid, 2) for _ in range(3)]
    assert set(histogram(rs)) == {202}, histogram(rs)
    ids = order_ids(rs)
    cancelled = await_until(lambda: all(status_of(stack, i) == "CANCELLED" for i in ids), timeout=90)
    assert cancelled, "orders were not cancelled after the waiting deadline"
    assert all("RESERVATION_TIMEOUT" in (cancel_reason(stack, i) or "") for i in ids), "wrong cancellation reason"
    stack.unpause("kafka")                                 # the commands now arrive, too late
    late = await_until(lambda: (stack.metric("order-service", "order_inventory_late_reservations_total") or 0) >= late0 + 3, timeout=120)
    assert late, "late reservations were not detected"
    return "3 orders cancelled by the deadline while Kafka was frozen; 3 late reservations detected and released after it returned"


@scenario("F16", "Inventory frozen when the waiting deadline passes", "ASYNC",
          "orders are NOT cancelled on a guess; recovery keeps asking with backoff and resolves them once Inventory answers")
def inventory_frozen(stack, fx):
    pid = fx.new_product(30)
    stack.pause("inventory-service")
    rs = [place_order(pid, 2) for _ in range(3)]
    assert set(histogram(rs)) == {202}, histogram(rs)
    ids = order_ids(rs)
    asked = await_until(lambda: all(saga_of(stack, i)[1] >= 2 for i in ids), timeout=120)
    assert asked, f"recovery did not keep asking: {[saga_of(stack, i) for i in ids]}"
    states = {saga_of(stack, i)[0] for i in ids}
    statuses = {status_of(stack, i) for i in ids}
    assert states == {"AWAITING_INVENTORY"} and statuses == {"PENDING"}, f"decided on a guess: {states} {statuses}"
    stack.unpause("inventory-service")
    return "while Inventory was frozen the 3 orders stayed PENDING/AWAITING_INVENTORY (retries with backoff, no guess); resolved after it thawed"


@scenario("F17", "Poison messages on the command and reply topics", "ASYNC",
          "each goes to its dead-letter topic after bounded retries and blocks nothing behind it", deadline="300s")
def poison(stack, fx):
    pid = fx.new_product(20)
    c0 = stack.topic_size("mercury.inventory.commands.dlq")
    e0 = stack.topic_size("mercury.inventory.events.dlq")
    stack.produce("mercury.inventory.commands", "poison", "this is not a command")
    stack.produce("mercury.inventory.events", "poison", "this is not an event")
    oid = place_order(pid, 1).body["id"]                    # right behind the poison
    assert await_until(lambda: status_of(stack, oid) == "CONFIRMED", timeout=120), "an order behind the poison never completed"
    ok = await_until(lambda: stack.topic_size("mercury.inventory.commands.dlq") >= c0 + 1
                     and stack.topic_size("mercury.inventory.events.dlq") >= e0 + 1, timeout=90)
    assert ok, "the poison messages did not reach their dead-letter topics"
    return "both poison messages dead-lettered (1 each); the order placed right behind them was CONFIRMED"


# =============================================================================== authentication (Phase 15)

@scenario("F18", "User Service killed (SIGKILL)", "SYNC",
          "tokens are verified locally, so orders keep working for everyone already logged in; new logins fail until it is back")
def user_service_killed(stack, fx):
    pid = fx.new_product(30)
    Auth.token("admin"), Auth.token("customer")
    warm = place_order(pid)                                    # also fetches and caches Order's own service token
    assert warm.status == 201, f"warm-up order -> {warm.status} {warm.body}"
    stack.kill("user-service")
    rs = [place_order(pid) for _ in range(5)]
    assert set(histogram(rs)) == {201}, f"existing sessions must keep working: {histogram(rs)}"
    login = http("POST", f"{URLS['user']}/api/v1/auth/login", {"email": "x@y.z", "password": "whatever-it-is"}, token=None, timeout=5)
    assert login.status == 0, f"a dead user-service answered a login: {login.status}"
    stack.start("user-service")
    stack.wait_healthy()
    Auth.forget()
    again = await_until(lambda: Auth.token("customer") and place_order(pid).status == 201, timeout=60, interval=2)
    assert again, "new logins and orders did not resume after the user-service returned"
    return "5 orders = 201 with existing tokens while it was dead (logins refused); after restart a fresh login and order succeeded"


@scenario("F19", "Flood of forged and expired tokens while real customers order", "SYNC",
          "every forged token is refused with 401, the service stays healthy, real orders keep succeeding quickly")
def forged_flood(stack, fx):
    pid = fx.new_product(60)
    customer = Auth.token("customer")
    forged = ["garbage", "a.b.c", customer[:-6] + "AAAAAA", customer.rsplit(".", 1)[0] + ".", "x" * 4000]
    codes, stop = [], threading.Event()

    def attack():
        i = 0
        while not stop.is_set():
            r = http("GET", f"{URLS['order']}/api/v1/orders/{uuid.uuid4()}", token=forged[i % len(forged)], timeout=10)
            codes.append(r.status)
            i += 1

    attackers = [threading.Thread(target=attack, daemon=True) for _ in range(8)]
    for t in attackers:
        t.start()
    time.sleep(1)
    legit = [place_order(pid, token=customer) for _ in range(10)]
    stop.set()
    for t in attackers:
        t.join(timeout=15)
    h = histogram(legit)
    slowest = max(r.seconds for r in legit)
    assert set(h) == {201}, f"real orders were affected by the flood: {h}"
    assert codes and set(codes) == {401}, f"forged tokens got through or errored: {sorted(set(codes))}"
    assert slowest < 5, f"a real order took {slowest:.1f}s during the flood"
    return f"{len(codes)} forged requests all 401; 10 real orders all 201, slowest {slowest:.2f}s"
