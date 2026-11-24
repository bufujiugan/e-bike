ALTER TABLE bike_part
    ADD COLUMN photo_object_key VARCHAR(255) NULL COMMENT '零部件图片存储键',
    ADD UNIQUE KEY uk_bike_part_photo_object (photo_object_key);
