package com.bike.server.production;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import org.springframework.security.core.context.SecurityContextHolder;

public final class ProductionAccess {
  private ProductionAccess() {}

  public static void read() {
    CurrentOperator.requireAnyPermission(
        "ASSEMBLY_OPERATE",
        "REWORK_OPERATE",
        "VEHICLE_READ",
        "STOCK_IN_OPERATE",
        "STOCK_OUT_OPERATE");
    if (CurrentOperator.hasRole("WEB_AGENT"))
      throw new BusinessException("PERMISSION_DENIED", "代理商不能访问生产作业");
  }

  public static void operate() {
    read();
    CurrentOperator.requirePermission("ASSEMBLY_OPERATE");
    requireStation(null);
  }

  public static void scan(String type) {
    operate();
    requireStation(type);
  }

  private static void requireStation(String type) {
    if (CurrentOperator.hasRole("SUPER_ADMIN")) return;
    var auth = SecurityContextHolder.getContext().getAuthentication();
    boolean allowed =
        auth.getAuthorities().stream()
            .anyMatch(
                a -> {
                  String value = a.getAuthority();
                  if (value.equals("WORKSTATION_ASSEMBLY")) return true;
                  return type == null
                      ? java.util.Set.of(
                              "WORKSTATION_FRAME",
                              "WORKSTATION_MOTOR",
                              "WORKSTATION_BATTERY",
                              "WORKSTATION_DISPLAY")
                          .contains(value)
                      : value.equals("WORKSTATION_" + type);
                });
    if (!allowed) throw new BusinessException("WORKSTATION_FORBIDDEN", "当前账号没有对应生产工位权限");
  }

  public static void manage() {
    CurrentOperator.requireRole("SUPER_ADMIN");
  }
}
