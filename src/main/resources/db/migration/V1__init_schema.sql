CREATE TABLE task (
    id              UUID                     PRIMARY KEY,
    trip_id         UUID                     NOT NULL,
    parent_task_id  UUID                     REFERENCES task(id),
    title           VARCHAR(255)             NOT NULL,
    description     TEXT,
    assignee_id     UUID,
    status          VARCHAR(50)              NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    priority        VARCHAR(50)              NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    deadline        TIMESTAMP WITH TIME ZONE,
    created_by      UUID                     NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_trip_id       ON task (trip_id);
CREATE INDEX idx_task_assignee_id   ON task (assignee_id);
CREATE INDEX idx_task_deadline      ON task (deadline);
CREATE INDEX idx_task_parent_task_id ON task (parent_task_id);
