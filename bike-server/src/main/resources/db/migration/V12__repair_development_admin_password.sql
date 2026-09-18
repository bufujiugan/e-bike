-- 仅修复仍处于首次登录状态的开发样例管理员；已修改过密码的账号不受影响。
UPDATE bike_sys_user
SET password_hash = '$2a$10$TqQFIEw1usKsgjDf0k46jugcHIAoC5FdqfmmpyaRiNrs9tddAQt9C',
    must_change_password = 1
WHERE username = 'admin'
  AND must_change_password = 1;
