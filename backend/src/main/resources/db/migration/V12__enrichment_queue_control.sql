-- What an admin decided about a resource in the enrichment queue (ADR 0045 §7). The Scout owns this table and is the
-- only writer; the catalog's backlog query reads it, so the Scout's next batch, the queue page and the count keep one
-- rule. One row per resource that an admin touched, gone with the resource.
--   prioritised_at:    moved to the top; it holds until the resource's next run starts.
--   skipped_at:        out of the backlog until an admin requeues it; skip_reason is optional.
--   failures_reset_at: requeued after its retries ran out; only FAILED runs after it count against the cap.
-- Who did it, and when, is in admin_audit.
CREATE TABLE enrichment_queue_control (
    resource_id       uuid        PRIMARY KEY REFERENCES resource (id) ON DELETE CASCADE,
    prioritised_at    timestamptz,
    skipped_at        timestamptz,
    skip_reason       text        CHECK (length(skip_reason) <= 200),
    failures_reset_at timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (prioritised_at IS NULL OR skipped_at IS NULL),
    CHECK (skip_reason IS NULL OR skipped_at IS NOT NULL)
);

-- The prioritised part of the backlog starts from these rows (at most 50 hold at a time); the other parts look a
-- resource up by its primary key.
CREATE INDEX enrichment_queue_control_prioritised_idx ON enrichment_queue_control (prioritised_at)
    WHERE prioritised_at IS NOT NULL;
-- The skipped view: the latest skip first, page by page.
CREATE INDEX enrichment_queue_control_skipped_idx ON enrichment_queue_control (skipped_at DESC, resource_id DESC)
    WHERE skipped_at IS NOT NULL;

-- An admin can requeue, skip and prioritise a resource in the enrichment queue.
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT'));
