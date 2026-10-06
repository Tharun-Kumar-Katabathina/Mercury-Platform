CREATE TABLE inventory (
    id                 UUID                     NOT NULL,
    product_id         UUID                     NOT NULL,
    available_quantity INTEGER                  NOT NULL,
    reserved_quantity  INTEGER                  NOT NULL,
    version            BIGINT                   NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (id),
    CONSTRAINT uk_inventory_product_id UNIQUE (product_id),
    -- last line of defence against overselling, independent of application logic
    CONSTRAINT ck_inventory_available_non_negative CHECK (available_quantity >= 0),
    CONSTRAINT ck_inventory_reserved_non_negative CHECK (reserved_quantity >= 0)
);
