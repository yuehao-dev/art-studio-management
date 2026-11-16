-- Existing production databases: run once with a schema administrator before starting the new JAR.
-- New databases already include this table in database/init.sql.
CREATE TABLE IF NOT EXISTS user_avatars (
 user_id BIGINT PRIMARY KEY,file_id BIGINT NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 CONSTRAINT fk_avatar_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_avatar_file FOREIGN KEY(file_id) REFERENCES files(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
