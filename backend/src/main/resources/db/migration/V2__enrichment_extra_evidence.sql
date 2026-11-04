-- ADR 0043: the README or model card an enrichment run read in its gap round ("github-readme:owner/name"), else null.
ALTER TABLE enrichment_run ADD COLUMN extra_evidence text CHECK (length(extra_evidence) <= 300);
