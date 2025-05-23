package aibe1.proj2.mentoss.feature.application.model.dto;

import java.util.List;

public record LectureApplicantDto(
        Long applicationId,
        String nickname,
        String lectureTitle,
        String createdAt,
        String profileImage,
        TimeSlot requestedTimeSlot
) {
}
