package io.specto.hoverfly.junit.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import com.sun.jersey.api.client.WebResource;
import io.specto.hoverfly.junit.core.model.Simulation;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.client.RestTemplate;
import org.zeroturnaround.exec.StartedProcess;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.specto.hoverfly.junit.core.HoverflyConfig.configs;
import static io.specto.hoverfly.junit.core.HoverflyMode.SIMULATE;
import static io.specto.hoverfly.junit.core.SimulationSource.classpath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.easymock.EasyMock.expect;
import static org.mockito.Mockito.*;
import static org.powermock.api.easymock.PowerMock.*;
import static org.springframework.http.HttpStatus.OK;

@PowerMockIgnore("javax.net.ssl.*")
@RunWith(PowerMockRunner.class)
@PrepareForTest(Hoverfly.class)
public class HoverflyTest {

    private static final int EXPECTED_PROXY_PORT = 8890;
    private Hoverfly hoverfly;
    private ObjectMapper mapper = new ObjectMapper();

    @Test
    public void shouldStartHoverflyOnConfiguredPort() throws Exception {
        hoverfly = new Hoverfly(configs().proxyPort(EXPECTED_PROXY_PORT), SIMULATE);
        hoverfly.start();
        assertThat(System.getProperty("http.proxyPort")).isEqualTo(String.valueOf(EXPECTED_PROXY_PORT));
        assertThat(hoverfly.getProxyPort()).isEqualTo(EXPECTED_PROXY_PORT);
    }

    @Test
    public void shouldDeleteTempFilesWhenStoppingHoverfly() throws Exception {
        startDefaultHoverfly();
        hoverfly.stop();
        final Field binaryPath = ReflectionUtils.findField(Hoverfly.class, "binaryPath", Path.class);
        binaryPath.setAccessible(true);
        assertThat(Files.exists((Path) binaryPath.get(hoverfly))).isFalse();
    }

    @Test
    public void shouldImportSimulation() throws Exception {
        startDefaultHoverfly();
        // When
        URL resource = Resources.getResource("test-service.json");
        Simulation importedSimulation = mapper.readValue(resource, Simulation.class);
        hoverfly.importSimulation(classpath("test-service.json"));

        // Then
        Simulation exportedSimulation = hoverfly.getSimulation();
        assertThat(exportedSimulation).isEqualTo(importedSimulation);
    }

    @Test
    public void shouldThrowExceptionWhenProxyPortIsAlreadyInUse() throws Exception {
        // Given
        startDefaultHoverfly();
        Hoverfly portClashHoverfly = new Hoverfly(configs().proxyPort(hoverfly.getProxyPort()), SIMULATE);

        try {
            // When
            Throwable throwable = catchThrowable(portClashHoverfly::start);

            //Then
            assertThat(throwable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Port is already in use");
        } finally {
            portClashHoverfly.stop();
        }
    }

    @Test
    public void shouldThrowExceptionWhenAdminPortIsAlreadyInUse() throws Exception {
        // Given
        startDefaultHoverfly();
        Hoverfly portClashHoverfly = new Hoverfly(configs().adminPort(hoverfly.getAdminPort()), SIMULATE);

        try {
            // When
            Throwable throwable = catchThrowable(portClashHoverfly::start);

            //Then
            assertThat(throwable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Port is already in use");
        } finally {
            portClashHoverfly.stop();
        }
    }

    @Test
    public void shouldBeAbleToUseRemoteHoverflyInstance() throws Exception {
        // Given
        startDefaultHoverfly();

        // When
        final Hoverfly remoteHoverfly = new Hoverfly(
                configs().useRemoteInstance()
                        .adminPort(hoverfly.getAdminPort())
                        .proxyPort(hoverfly.getProxyPort()),
                SIMULATE);

        // Then
        assertRemoteHoverflyIsWorking(remoteHoverfly);
    }


    @Test
    public void shouldSetDefaultConfigForRemoteHoverflyInstance() throws Exception {

        Hoverfly remoteHoverfly = new Hoverfly(configs().useRemoteInstance(), SIMULATE);

        URI hoverflyUrl = getHoverflyUrl(remoteHoverfly);

        assertThat(hoverflyUrl.toString()).isEqualTo("http://localhost:8888");
        assertThat(remoteHoverfly.getProxyPort()).isEqualTo(8500);
        assertThat(remoteHoverfly.getAdminPort()).isEqualTo(8888);
    }

    @Test
    public void shouldSetCustomConfigForRemoteHoverflyInstance() throws Exception {

        Hoverfly remoteHoverfly = new Hoverfly(configs().useRemoteInstance("http://hoverfly-cloud").proxyPort(8000).adminPort(9000), SIMULATE);

        URI hoverflyUrl = getHoverflyUrl(remoteHoverfly);

        assertThat(hoverflyUrl.toString()).isEqualTo("http://hoverfly-cloud:9000");
        assertThat(remoteHoverfly.getProxyPort()).isEqualTo(8000);
        assertThat(remoteHoverfly.getAdminPort()).isEqualTo(9000);

    }

    @Test
    public void shouldNotOverrideDefaultTrustManager() throws Exception {
        startDefaultHoverfly();

        HttpClient client = HttpClientBuilder.create().setSSLContext(SSLContext.getDefault()).build();

        // TODO: Find better way to test trust store
        HttpResponse response = client.execute(new HttpGet("https://specto.io"));
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(HttpStatus.OK.value());
    }


    @Test
    public void shouldWaitForHoverflyProcessTerminatedBeforeDeletingBinary() throws Exception {
        Hoverfly hoverfly = new Hoverfly(SIMULATE);

        Field binaryPath = hoverfly.getClass().getDeclaredField("binaryPath");
        binaryPath.setAccessible(true);
        Path mockPath = mock(Path.class);
        binaryPath.set(hoverfly, mockPath);

        File mockFile = mock(File.class);
        when(mockPath.toFile()).thenReturn(mockFile);

        mockStatic(Files.class);
        expect(Files.deleteIfExists(mockPath)).andThrow(new IOException());
        replayAll();

        Field startedProcess = hoverfly.getClass().getDeclaredField("startedProcess");
        startedProcess.setAccessible(true);
        StartedProcess mockStartedProcess = mock(StartedProcess.class);
        Process mockProcess = mock(Process.class);
        startedProcess.set(hoverfly, mockStartedProcess);

        when(mockStartedProcess.getProcess()).thenReturn(mockProcess);

        // When
        hoverfly.stop();

        // Then
        InOrder inOrder = inOrder(mockProcess, mockFile);
        inOrder.verify(mockProcess).destroy();
        inOrder.verify(mockProcess).waitFor();
        inOrder.verify(mockFile).deleteOnExit();
        verifyAll();
    }


    private void assertRemoteHoverflyIsWorking(final Hoverfly hoverfly) {
        try {
            hoverfly.start();
            hoverfly.importSimulation(classpath("test-service.json"));
            final ResponseEntity<String> getBookingResponse = new RestTemplate().getForEntity("http://www.my-test.com/api/bookings/1", String.class);

            // Then
            assertThat(hoverfly.getSimulation()).isNotNull();
            assertThat(getBookingResponse.getStatusCode()).isEqualTo(OK);
        } finally {
            hoverfly.stop();
        }
    }

    @After
    public void tearDown() throws Exception {
        if (hoverfly != null) {
            hoverfly.stop();
        }
    }

    private void startDefaultHoverfly() throws IOException, URISyntaxException {
        hoverfly = new Hoverfly(SIMULATE);
        hoverfly.start();
    }

    private URI getHoverflyUrl(Hoverfly remoteHoverfly) throws NoSuchFieldException, IllegalAccessException {
        Field hoverflyResource = remoteHoverfly.getClass().getDeclaredField("hoverflyResource");
        hoverflyResource.setAccessible(true);
        return ((WebResource) hoverflyResource.get(remoteHoverfly)).getURI();
    }
}
