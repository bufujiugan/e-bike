package com.bike.server.rework;

import com.bike.server.common.PageResponse;

public interface ReworkService { ReworkView create(ReworkRequest request); ReworkView get(long id); PageResponse<ReworkView> list(long page,long pageSize); }
