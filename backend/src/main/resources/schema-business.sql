CREATE TABLE IF NOT EXISTS schedule_guard (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT IGNORE INTO schedule_guard(id) VALUES (1);
CREATE TABLE IF NOT EXISTS enrollments (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, student_id BIGINT NOT NULL, class_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', effective_date DATE NOT NULL, end_date DATE NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 active_key VARCHAR(80) GENERATED ALWAYS AS (IF(status='ACTIVE',CONCAT(student_id,':',class_id),NULL)) STORED,
 UNIQUE KEY uk_enrollment_active(active_key), KEY ix_enrollment_class(class_id,effective_date,end_date),
 FOREIGN KEY(student_id) REFERENCES students(id), FOREIGN KEY(class_id) REFERENCES classes(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS transfers (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, enrollment_id BIGINT NOT NULL, new_enrollment_id BIGINT NOT NULL,
 student_id BIGINT NOT NULL, from_class_id BIGINT NOT NULL, to_class_id BIGINT NOT NULL,
 effective_date DATE NOT NULL, reason VARCHAR(1000) NOT NULL, created_by BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(enrollment_id) REFERENCES enrollments(id), FOREIGN KEY(new_enrollment_id) REFERENCES enrollments(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS lessons (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, class_id BIGINT NOT NULL, teacher_id BIGINT NOT NULL, room_id BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL, start_time DATETIME NOT NULL, end_time DATETIME NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED', cancel_reason VARCHAR(1000), created_by BIGINT,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY ix_lesson_time(start_time,end_time), KEY ix_lesson_class(class_id),
 FOREIGN KEY(class_id) REFERENCES classes(id), FOREIGN KEY(teacher_id) REFERENCES teachers(id), FOREIGN KEY(room_id) REFERENCES rooms(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS lesson_participants (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, lesson_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
 kind VARCHAR(20) NOT NULL DEFAULT 'REGULAR', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 original_participant_id BIGINT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_lesson_student(lesson_id,student_id), KEY ix_participant_student(student_id),
 FOREIGN KEY(lesson_id) REFERENCES lessons(id), FOREIGN KEY(student_id) REFERENCES students(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS attendance (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, participant_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
 remark VARCHAR(1000), recorded_by BIGINT NOT NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_attendance_participant(participant_id), FOREIGN KEY(participant_id) REFERENCES lesson_participants(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS leaves (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, participant_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
 lesson_id BIGINT NOT NULL, reason VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 review_comment VARCHAR(1000), reviewed_by BIGINT, reviewed_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_leave_participant(participant_id), FOREIGN KEY(participant_id) REFERENCES lesson_participants(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS makeups (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, original_participant_id BIGINT NOT NULL, target_lesson_id BIGINT NOT NULL,
 target_participant_id BIGINT NOT NULL, student_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ARRANGED',
 reason VARCHAR(1000), created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at DATETIME, cancelled_reason VARCHAR(1000),
 active_origin BIGINT GENERATED ALWAYS AS (IF(status IN ('ARRANGED','COMPLETED'),original_participant_id,NULL)) STORED,
 UNIQUE KEY uk_makeup_origin(active_origin), FOREIGN KEY(original_participant_id) REFERENCES lesson_participants(id),
 FOREIGN KEY(target_lesson_id) REFERENCES lessons(id), FOREIGN KEY(target_participant_id) REFERENCES lesson_participants(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS makeup_suggestions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, original_participant_id BIGINT NOT NULL, comment VARCHAR(1000) NOT NULL,
 teacher_id BIGINT, created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(original_participant_id) REFERENCES lesson_participants(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tasks (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, class_id BIGINT NOT NULL, lesson_id BIGINT,
 title VARCHAR(200) NOT NULL, description TEXT, due_date DATE, created_by BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(class_id) REFERENCES classes(id), FOREIGN KEY(lesson_id) REFERENCES lessons(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS works (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_work_task_student(task_id,student_id), FOREIGN KEY(task_id) REFERENCES tasks(id), FOREIGN KEY(student_id) REFERENCES students(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS work_versions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, work_id BIGINT NOT NULL, file_id BIGINT NOT NULL,
 version_no INT NOT NULL, description TEXT, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_work_version(work_id,version_no), FOREIGN KEY(work_id) REFERENCES works(id), FOREIGN KEY(file_id) REFERENCES files(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS reviews (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, version_id BIGINT NOT NULL, teacher_id BIGINT,
 content TEXT NOT NULL, score INT, created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(version_id) REFERENCES work_versions(id), FOREIGN KEY(teacher_id) REFERENCES teachers(id)
) ENGINE=InnoDB;
