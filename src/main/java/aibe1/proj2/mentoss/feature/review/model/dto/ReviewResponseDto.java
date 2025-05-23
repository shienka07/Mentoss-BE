package aibe1.proj2.mentoss.feature.review.model.dto;

import java.time.LocalDateTime;

public record ReviewResponseDto(
        Long reviewId,
        Long lectureId,
        Long mentorId,
        Long writerId,
        String writerNickname,
        String writerProfileImage,
        String content,
        Long rating,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}