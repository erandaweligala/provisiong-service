package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BngRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.BngEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PingServiceTest {

    private BngRepository bngRepository;
    private PingService pingService;

    @BeforeEach
    void setUp() {
        bngRepository = mock(BngRepository.class);
        pingService = spy(new PingService(bngRepository));
    }

    @Test
    void testPingBngNotFound() {
        String bngId = "missing-id";
        when(bngRepository.findById(bngId)).thenReturn(Optional.empty());

        String result = pingService.ping(bngId);

        assertEquals("NOT_FOUND", result);
    }

    @Test
    void testPingInetSuccess() throws Exception {
        String bngId = "bng1";
        String ip = "192.168.1.1";
        BngEntity bng = new BngEntity();
        bng.setNasIpAddress(ip);

        when(bngRepository.findById(bngId)).thenReturn(Optional.of(bng));
        doReturn(true).when(pingService).inetPing(ip);

        String result = pingService.ping(bngId);

        assertEquals("SUCCESS", result);
        verify(pingService, times(1)).inetPing(ip);
    }

    @Test
    void testPingSystemSuccessWhenInetFails() throws Exception {
        String bngId = "bng2";
        String ip = "192.168.1.2";
        BngEntity bng = new BngEntity();
        bng.setNasIpAddress(ip);

        when(bngRepository.findById(bngId)).thenReturn(Optional.of(bng));
        doReturn(false).when(pingService).inetPing(ip);
        doReturn(true).when(pingService).systemPing(ip);

        String result = pingService.ping(bngId);

        assertEquals("SUCCESS", result);
        verify(pingService).systemPing(ip);
    }

    @Test
    void testPingTcpSuccessWhenInetAndSystemFail() throws Exception {
        String bngId = "bng3";
        String ip = "192.168.1.3";
        BngEntity bng = new BngEntity();
        bng.setNasIpAddress(ip);

        when(bngRepository.findById(bngId)).thenReturn(Optional.of(bng));
        doReturn(false).when(pingService).inetPing(ip);
        doReturn(false).when(pingService).systemPing(ip);
        doReturn(true).when(pingService).tcpPing(ip, 22);

        String result = pingService.ping(bngId);

        assertEquals("SUCCESS", result);
        verify(pingService).tcpPing(ip, 22);
    }

    @Test
    void testPingAllFail() throws Exception {
        String bngId = "bng4";
        String ip = "192.168.1.4";
        BngEntity bng = new BngEntity();
        bng.setNasIpAddress(ip);

        when(bngRepository.findById(bngId)).thenReturn(Optional.of(bng));
        doReturn(false).when(pingService).inetPing(ip);
        doReturn(false).when(pingService).systemPing(ip);
        doReturn(false).when(pingService).tcpPing(anyString(), anyInt());

        String result = pingService.ping(bngId);

        assertEquals("FAILED", result);
    }



    @Test
    void testInetPingSuccess() throws Exception {
        String ip = "127.0.0.1";

        try (MockedStatic<InetAddress> mockedStatic = mockStatic(InetAddress.class)) {
            InetAddress inetAddressMock = mock(InetAddress.class);
            mockedStatic.when(() -> InetAddress.getByName(ip)).thenReturn(inetAddressMock);
            when(inetAddressMock.isReachable(5000)).thenReturn(true);

            boolean reachable = pingService.inetPing(ip);

            assertTrue(reachable);
        }
    }

    @Test
    void testInetPingFailure() throws Exception {
        String ip = "127.0.0.2";

        try (MockedStatic<InetAddress> mockedStatic = mockStatic(InetAddress.class)) {
            InetAddress inetAddressMock = mock(InetAddress.class);
            mockedStatic.when(() -> InetAddress.getByName(ip)).thenReturn(inetAddressMock);
            when(inetAddressMock.isReachable(5000)).thenReturn(false);

            boolean reachable = pingService.inetPing(ip);

            assertFalse(reachable);
        }
    }

    @Test
    void testTcpPingSuccess() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            boolean result = pingService.tcpPing("127.0.0.1", port);
            assertTrue(result);
        }
    }
}