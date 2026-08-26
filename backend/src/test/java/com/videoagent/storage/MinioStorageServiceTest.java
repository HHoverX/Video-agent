package com.videoagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class MinioStorageServiceTest {

    private final ObjectProvider<MinioClient> internalClientProvider = mock(ObjectProvider.class);
    private final ObjectProvider<MinioClient> publicPresignClientProvider = mock(ObjectProvider.class);
    private final MinioClient internalClient = mock(MinioClient.class);
    private final MinioClient publicPresignClient = mock(MinioClient.class);
    private MinioStorageService storageService;

    @BeforeEach
    void setUp() {
        when(internalClientProvider.getObject()).thenReturn(internalClient);
        when(publicPresignClientProvider.getObject()).thenReturn(publicPresignClient);
        storageService = new MinioStorageService(
            internalClientProvider,
            publicPresignClientProvider,
            new StorageProperties(
                "http://minio.internal:9000",
                "https://media.example.com",
                "access-key",
                "secret-key",
                "videoagent"
            )
        );
    }

    @Test
    void shouldPresignGetWithPublicClientAndExpectedArguments() throws Exception {
        when(publicPresignClient.getPresignedObjectUrl(any())).thenReturn("https://media.example.com/signed-get");

        String url = storageService.presignGetObject("videos/owned.mp4", Duration.ofMinutes(60));

        assertThat(url).isEqualTo("https://media.example.com/signed-get");
        ArgumentCaptor<GetPresignedObjectUrlArgs> arguments =
            ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(publicPresignClient).getPresignedObjectUrl(arguments.capture());
        assertThat(arguments.getValue().method()).isEqualTo(Method.GET);
        assertThat(arguments.getValue().bucket()).isEqualTo("videoagent");
        assertThat(arguments.getValue().object()).isEqualTo("videos/owned.mp4");
        assertThat(arguments.getValue().expiry()).isEqualTo(3_600);
        verifyNoInteractions(internalClient);
    }

    @Test
    void shouldPresignPutWithPublicClient() throws Exception {
        when(internalClient.bucketExists(any())).thenReturn(true);
        when(publicPresignClient.getPresignedObjectUrl(any())).thenReturn("https://media.example.com/signed-put");

        String url = storageService.presignPutObject("uploads/part-1", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://media.example.com/signed-put");
        ArgumentCaptor<GetPresignedObjectUrlArgs> arguments =
            ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(publicPresignClient).getPresignedObjectUrl(arguments.capture());
        assertThat(arguments.getValue().method()).isEqualTo(Method.PUT);
        assertThat(arguments.getValue().bucket()).isEqualTo("videoagent");
        assertThat(arguments.getValue().object()).isEqualTo("uploads/part-1");
        assertThat(arguments.getValue().expiry()).isEqualTo(900);
        verify(internalClient).bucketExists(any());
    }

    @Test
    void shouldMapPresignSdkFailureToStorageError() throws Exception {
        when(publicPresignClient.getPresignedObjectUrl(any())).thenThrow(new RuntimeException("minio unavailable"));

        assertThatThrownBy(() -> storageService.presignGetObject("videos/owned.mp4", Duration.ofMinutes(60)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_ERROR)
            );
    }
}
