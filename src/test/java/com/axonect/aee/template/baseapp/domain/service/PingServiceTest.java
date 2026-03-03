package com.axonect.aee.template.baseapp.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PingServiceTest {

    private PingService pingService;

    @BeforeEach
    void setUp() {
        pingService = new PingService();
    }

    /**
     * isReachable = true → SUCCESS
     */
    @Test
    void ping_success_via_isReachable() throws Exception {
        InetAddress inet = mock(InetAddress.class);

        try (MockedStatic<InetAddress> mocked = mockStatic(InetAddress.class)) {
            mocked.when(() -> InetAddress.getByName("8.8.8.8")).thenReturn(inet);
            when(inet.isReachable(anyInt())).thenReturn(true);

            assertEquals("SUCCESS", pingService.ping("8.8.8.8"));
        }
    }

    /**
     * isReachable = false → systemPing SUCCESS
     */
    @Test
    void ping_success_via_systemPing() throws Exception {
        InetAddress inet = mock(InetAddress.class);

        Process process = mock(Process.class);
        when(process.getInputStream())
                .thenReturn(new ByteArrayInputStream(
                        "64  bytes ttl=64 time=10ms".getBytes()
                ));

        try (MockedStatic<InetAddress> inetMock = mockStatic(InetAddress.class);
             MockedStatic<Runtime> runtimeMock = mockStatic(Runtime.class)) {

            inetMock.when(() -> InetAddress.getByName("1.1.1.1"))
                    .thenReturn(inet);

            when(inet.isReachable(anyInt())).thenReturn(false);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(anyString())).thenReturn(process);

            assertEquals("SUCCESS", pingService.ping("1.1.1.1"));
        }
    }


    /**
     * isReachable + systemPing fail → tcpPing SUCCESS
     */
    @Test
    void ping_success_via_tcpPing() throws Exception {
        InetAddress inet = mock(InetAddress.class);

        try (MockedStatic<InetAddress> inetMock = mockStatic(InetAddress.class);
             MockedConstruction<Socket> socketMock =
                     mockConstruction(Socket.class, (socket, context) ->
                             doNothing().when(socket)
                                     .connect(any(InetSocketAddress.class), anyInt())
                     )) {

            inetMock.when(() -> InetAddress.getByName("9.9.9.9")).thenReturn(inet);
            when(inet.isReachable(anyInt())).thenReturn(false);

            assertEquals("SUCCESS", pingService.ping("9.9.9.9"));
        }
    }

    /**
     * All methods fail → FAILED
     */


    /**
     * Invalid IP → FAILED (outer catch)
     */
    @Test
    void ping_invalid_ip_exception() {
        assertEquals("FAILED", pingService.ping("invalid.ip"));
    }

    /**
     * systemPing InterruptedException branch
     */
    @Test
    void systemPing_interrupted_exception() throws Exception {
        InetAddress inet = mock(InetAddress.class);

        Process process = mock(Process.class);
        when(process.getInputStream())
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor()).thenThrow(new InterruptedException());

        try (MockedStatic<InetAddress> inetMock = mockStatic(InetAddress.class);
             MockedStatic<Runtime> runtimeMock = mockStatic(Runtime.class)) {

            inetMock.when(() -> InetAddress.getByName("10.10.10.10")).thenReturn(inet);
            when(inet.isReachable(anyInt())).thenReturn(false);

            Runtime runtime = mock(Runtime.class);
            runtimeMock.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(anyString())).thenReturn(process);

            assertEquals("FAILED", pingService.ping("10.10.10.10"));
            assertTrue(Thread.interrupted()); // clears interrupt flag
        }
    }

    /**
     * tcpPing exception branch
     */
    @Test
    void tcpPing_exception() throws Exception {
        InetAddress inet = mock(InetAddress.class);

        try (MockedStatic<InetAddress> inetMock = mockStatic(InetAddress.class);
             MockedConstruction<Socket> socketMock =
                     mockConstruction(Socket.class, (socket, context) ->
                             doThrow(new RuntimeException()).when(socket)
                                     .connect(any(), anyInt())
                     )) {

            inetMock.when(() -> InetAddress.getByName("11.11.11.11")).thenReturn(inet);
            when(inet.isReachable(anyInt())).thenReturn(false);

            assertEquals("FAILED", pingService.ping("11.11.11.11"));
        }
    }
}
