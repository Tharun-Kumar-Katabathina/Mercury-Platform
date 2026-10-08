# Baseline vs optimized: summary of every valid repetition

improvement % = (baseline - optimized) / baseline: **positive = optimized faster, negative = optimized slower**.

A level counts only if both profiles had an error rate under 1 %.  The claim under test is a 35 % reduction.

## `mixed` scenario

| rep | power | users | p50 base→opt ms | p95 base→opt ms | p99 base→opt ms | p50 | p95 | p99 | req/s base→opt | errors % base→opt | counts |
|---|---|---|---|---|---|---|---|---|---|---|---|
| rep1 | not recorded | 100 | 4→5 | 42→60 | 101→155 | -22.4% | -42.2% | -53.6% | 27.1→26.8 | 0→0 | yes |
| rep1 | not recorded | 500 | 2→3 | 21→54 | 48→134 | -70.8% | -157.7% | -178.0% | 131.3→129.8 | 0→0 | yes |
| rep1 | not recorded | 1,000 | 2→138 | 88→5388 | 423→8264 | -7485.7% | -6030.0% | -1854.6% | 259.0→195.9 | 0→0.735 | yes |
| rep2 | Battery Power | 100 | 4→8 | 42→144 | 123→475 | -108.8% | -246.8% | -287.3% | 27.0→26.3 | 0→0 | yes |
| rep2 | Battery Power | 500 | 2→56 | 57→6008 | 552→11437 | -2470.5% | -10396.7% | -1970.4% | 130.1→92.8 | 0→0.405 | yes |
| rep2 | Battery Power | 1,000 | 2→1069 | 1070→19944 | 3002→25342 | -55121.9% | -1764.1% | -744.2% | 250.5→97.0 | 0→0.331 | yes |
| rep3 | Battery Power | 100 | 5→112 | 132→4982 | 495→6227 | -1950.9% | -3660.1% | -1157.5% | 26.7→20.7 | 0→0 | yes |
| rep3 | Battery Power | 500 | 3→5451 | 36→16167 | 117→20692 | -204701.3% | -44979.1% | -17584.4% | 129.7→40.0 | 0→3.407 | no (errors) |
| rep3 | Battery Power | 1,000 | 4→5007 | 1020→27930 | 3020→37597 | -122620.6% | -2638.3% | -1144.9% | 247.3→48.6 | 0→4.073 | no (errors) |
| rep4 | AC Power | 100 | 5→19 | 55→1654 | 190→4837 | -278.2% | -2921.6% | -2442.7% | 26.6→22.3 | 0→0 | yes |
| rep4 | AC Power | 500 | 14→1189 | 569→24765 | 1038→37770 | -8672.9% | -4248.8% | -3538.0% | 125.0→43.0 | 0→2.775 | no (errors) |
| rep4 | AC Power | 1,000 | 1097→2800 | 7694→41047 | 13236→51757 | -155.4% | -433.5% | -291.0% | 110.3→60.9 | 1.47→4.462 | no (errors) |

### `mixed`: improvement across repetitions (only levels with errors under 1 % in both profiles)

| users | metric | valid reps | median | min | max | reps reaching 35 % | verdict |
|---|---|---|---|---|---|---|---|
| 100 | p50 | 4 | -193.5% | -1950.9% | -22.4% | 0 of 4 | never reached (optimized slower) |
| 100 | p95 | 4 | -1584.2% | -3660.1% | -42.2% | 0 of 4 | never reached (optimized slower) |
| 100 | p99 | 4 | -722.4% | -2442.7% | -53.6% | 0 of 4 | never reached (optimized slower) |
| 500 | p50 | 2 | -1270.6% | -2470.5% | -70.8% | 0 of 2 | never reached (optimized slower) |
| 500 | p95 | 2 | -5277.2% | -10396.7% | -157.7% | 0 of 2 | never reached (optimized slower) |
| 500 | p99 | 2 | -1074.2% | -1970.4% | -178.0% | 0 of 2 | never reached (optimized slower) |
| 1,000 | p50 | 2 | -31303.8% | -55121.9% | -7485.7% | 0 of 2 | never reached (optimized slower) |
| 1,000 | p95 | 2 | -3897.0% | -6030.0% | -1764.1% | 0 of 2 | never reached (optimized slower) |
| 1,000 | p99 | 2 | -1299.4% | -1854.6% | -744.2% | 0 of 2 | never reached (optimized slower) |

Repository headline (compare.py: mean over the counting levels), per repetition: rep1: p50 -2526%, p95 -2077%, p99 -695%; rep2: p50 -19234%, p95 -4136%, p99 -1001%; rep3: p50 -1951%, p95 -3660%, p99 -1157%; rep4: p50 -278%, p95 -2922%, p99 -2443%

## `browse` scenario

| rep | power | users | p50 base→opt ms | p95 base→opt ms | p99 base→opt ms | p50 | p95 | p99 | req/s base→opt | errors % base→opt | counts |
|---|---|---|---|---|---|---|---|---|---|---|---|
| rep1 | AC Power | 100 | 8→8 | 366→3354 | 2262→7338 | -0.6% | -817.6% | -224.4% | 24.6→23.7 | 0→0 | yes |
| rep1 | AC Power | 500 | 3→3 | 772→29 | 1785→106 | +0.6% | +96.2% | +94.1% | 123.5→128.4 | 0→0 | yes |
| rep1 | AC Power | 1,000 | 2→2 | 1825→29 | 4415→129 | -12.5% | +98.4% | +97.1% | 241.1→256.3 | 0→0 | yes |
| rep2 | AC Power | 100 | 4→7 | 12→31 | 31→74 | -56.9% | -151.9% | -140.2% | 25.9→26.5 | 0→0 | yes |
| rep2 | AC Power | 500 | 2→3 | 161→4001 | 593→7602 | -78.2% | -2384.4% | -1182.8% | 129.9→112.0 | 0→0 | yes |
| rep2 | AC Power | 1,000 | 2→3 | 12→267 | 263→1849 | -83.4% | -2187.7% | -602.5% | 257.3→244.9 | 0→0 | yes |
| rep3 | AC Power | 100 | 4→8 | 16→95 | 51→1396 | -82.6% | -505.2% | -2653.2% | 26.8→25.1 | 0→0 | yes |
| rep3 | AC Power | 500 | 2→6 | 53→109 | 213→259 | -169.9% | -105.0% | -21.9% | 129.5→126.5 | 0→0 | yes |
| rep3 | AC Power | 1,000 | 2→2129 | 1236→22005 | 3431→25625 | -121981.1% | -1681.0% | -647.0% | 245.7→80.3 | 0→0 | yes |

### `browse`: improvement across repetitions (only levels with errors under 1 % in both profiles)

| users | metric | valid reps | median | min | max | reps reaching 35 % | verdict |
|---|---|---|---|---|---|---|---|
| 100 | p50 | 3 | -56.9% | -82.6% | -0.6% | 0 of 3 | never reached (optimized slower) |
| 100 | p95 | 3 | -505.2% | -817.6% | -151.9% | 0 of 3 | never reached (optimized slower) |
| 100 | p99 | 3 | -224.4% | -2653.2% | -140.2% | 0 of 3 | never reached (optimized slower) |
| 500 | p50 | 3 | -78.2% | -169.9% | +0.6% | 0 of 3 | never reached (optimized slower) |
| 500 | p95 | 3 | -105.0% | -2384.4% | +96.2% | 1 of 3 | inconsistent (1 of 3) |
| 500 | p99 | 3 | -21.9% | -1182.8% | +94.1% | 1 of 3 | inconsistent (1 of 3) |
| 1,000 | p50 | 3 | -83.4% | -121981.1% | -12.5% | 0 of 3 | never reached (optimized slower) |
| 1,000 | p95 | 3 | -1681.0% | -2187.7% | +98.4% | 1 of 3 | inconsistent (1 of 3) |
| 1,000 | p99 | 3 | -602.5% | -647.0% | +97.1% | 1 of 3 | inconsistent (1 of 3) |

Repository headline (compare.py: mean over the counting levels), per repetition: rep1: p50 -4%, p95 -208%, p99 -11%; rep2: p50 -73%, p95 -1575%, p99 -642%; rep3: p50 -40745%, p95 -764%, p99 -1107%
