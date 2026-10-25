package org.heigit.ors.api.config;

import jakarta.servlet.AsyncEvent;
import org.heigit.ors.api.controllers.RoutingAPI;
import org.heigit.ors.api.services.RoutingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

class LogicalRouteCancellationTest {
    @Test void servletTimeoutInterruptsTheRunningCalculation() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var service = mock(RoutingService.class);
        when(service.getEncoderForProfile("driving-car")).thenReturn(org.heigit.ors.common.EncoderNameEnum.DRIVING_CAR);
        when(service.generateLogicalRoute(any(), any())).thenAnswer(call -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("The calculation was not interrupted");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        });
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        try {
            var controller = new RoutingAPI(new EndpointsProperties(), new SystemMessageProperties(), service,
                    new MockHttpServletRequest(), executor);
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            var result = mvc.perform(post("/v2/directions/driving-car/logical")
                    .contentType("application/json")
                    .content("{\"groups\":[{\"coordinates\":[[21,52],[21.01,52.01]]}],\"exceptions\":[]}"))
                    .andExpect(request().asyncStarted()).andReturn();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var context = (MockAsyncContext) result.getRequest().getAsyncContext();
            for (var listener : context.getListeners()) listener.onTimeout(new AsyncEvent(context));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally { executor.shutdown(); }
    }
}
