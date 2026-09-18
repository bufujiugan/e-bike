-- 撤销出库不会删除历史记录，同一订单车辆再次出库时需要保留新的关联记录。
ALTER TABLE bike_sales_order_vehicle
    DROP INDEX uk_bike_order_vehicle,
    ADD KEY idx_bike_order_vehicle_order_vehicle (order_id, vehicle_id);
