ALTER TABLE resource DROP CONSTRAINT resource_type_check;
ALTER TABLE resource ADD CONSTRAINT resource_type_check CHECK (type IN
    ('PRODUCT', 'MODEL', 'AGENT', 'PROMPT', 'SKILL', 'MCP_SERVER', 'LIBRARY', 'DATASET', 'ARTICLE', 'PAPER', 'NEWS',
     'EVENT', 'COURSE', 'AWESOME_LIST', 'OTHER'));
