ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM h2_public;

CREATE TABLE public_app_lock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON public_app_lock TO h2_public;

REVOKE SELECT, INSERT, UPDATE ON shedlock FROM h2_public;
