package com.bike.server.production;

import com.bike.server.common.*;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanFailureService {
  private final ScanFailureMapper mapper;
  public ScanFailureService(ScanFailureMapper mapper) { this.mapper=mapper; }

  // 独立表无业务外键，不申请生产互斥锁，避免与被挂起的业务事务互相等待。
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String taskId, ProductionContracts.ScanRequest request, BusinessException error) {
    mapper.insert(limit(taskId,64), limit(request.code()==null ? "" : request.code(),255), limit(request.expectedType(),32),
        limit(CurrentOperator.username(),100), limit(error.getCode(),64), limit(error.getMessage(),500));
  }
  public PageResponse<Map<String,Object>> list(String keyword, long page) {
    ProductionAccess.read();
    CurrentOperator.requireWebAccess();
    long p=Math.min(10_000_000,Math.max(1,page));
    String query=keyword==null ? null : keyword.trim();
    return new PageResponse<>(mapper.select(query,(p-1)*50),p,50,mapper.count(query));
  }
  private static String limit(String value,int max) {
    return value==null ? null : value.substring(0,Math.min(value.length(),max));
  }
}
