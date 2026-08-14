package com.digixmed.cloud.icu.controller;

import com.digixmed.cloud.icu.service.IntermediateService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 体温单接口控制器（新链路）
 *
 * 仅保留必要的运维接口，旧的手动触发接口已移除。
 */
@RestController
@Api(value = "体温单接口", tags = {"体温单接口"})
public class Controller {

    private static final Logger log = LoggerFactory.getLogger(Controller.class);

    @Autowired
    private IntermediateService intermediateService;

    @GetMapping("/health")
    @ApiOperation(value = "健康检查", notes = "服务健康检查接口")
    public String health() {
        return "OK";
    }

    @GetMapping("/queue/stats")
    @ApiOperation(value = "队列统计", notes = "查看推送队列状态统计")
    public String queueStats() {
        // TODO: 实现队列统计
        return "Queue stats endpoint - to be implemented";
    }
}
