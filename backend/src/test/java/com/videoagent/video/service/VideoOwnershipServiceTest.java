package com.videoagent.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

class VideoOwnershipServiceTest {

    private final VideoRepository repository = mock(VideoRepository.class);
    private final VideoOwnershipService service = new VideoOwnershipService(repository);

    @BeforeEach
    void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "ownership-test"),
            VideoEntity.class
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRequireBothVideoIdAndCurrentUserId() {
        VideoEntity owned = new VideoEntity();
        owned.setId(42L);
        owned.setUserId(5L);
        when(repository.selectOne(any())).thenReturn(owned);

        assertThat(service.requireOwned(42L, 5L)).isSameAs(owned);

        ArgumentCaptor<Wrapper<VideoEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(repository).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("id", "user_id");
    }

    @Test
    void shouldReturnNotFoundForAnotherUsersVideo() {
        when(repository.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.requireOwned(42L, 6L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND)
            );
    }
}
