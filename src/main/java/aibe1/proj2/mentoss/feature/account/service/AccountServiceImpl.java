package aibe1.proj2.mentoss.feature.account.service;

import aibe1.proj2.mentoss.feature.account.model.dto.*;
import aibe1.proj2.mentoss.feature.account.model.mapper.AccountMapper;
import aibe1.proj2.mentoss.feature.account.model.mapper.MentorMapper;
import aibe1.proj2.mentoss.feature.lecture.model.mapper.LectureMapper;
import aibe1.proj2.mentoss.feature.login.model.mapper.AppUserMapper;
import aibe1.proj2.mentoss.feature.message.model.mapper.MessageMapper;
import aibe1.proj2.mentoss.feature.region.model.dto.RegionDto;
import aibe1.proj2.mentoss.global.entity.AppUser;
import aibe1.proj2.mentoss.global.entity.MentorProfile;
import aibe1.proj2.mentoss.global.entity.Region;
import aibe1.proj2.mentoss.global.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private final AccountMapper accountMapper;
    private final FileService fileService;
    private final MentorMapper mentorMapper;
    private final LectureMapper lectureMapper;
    private final MessageMapper messageMapper;
    private final AppUserMapper appUserMapper;
    private final DataSource dataSource;

    @Override
    public ProfileResponseDto getProfile(Long userId) {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));

        List<Region> regions = accountMapper.findRegionByUserId(userId);

        return ProfileResponseDto.fromEntity(appUser, regions);
    }

    @Override
    @Transactional
    public void updateProfile(Long userId, ProfileUpdateRequestDto requestDto) {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));

        if (requestDto.nickname() != null && requestDto.nickname().length() > 15) {
            throw new IllegalArgumentException("닉네임은 12자 이하로 입력해주세요.");
        }

        if (!appUser.getNickname().equals(requestDto.nickname())
                && appUserMapper.nicknameExists(requestDto.nickname())){
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다. 다른 닉네임을 선택해주세요.");

        }

        Long age = calculateAge(requestDto.birthDate());

        appUser.setNickname(requestDto.nickname());
        appUser.setBirthDate(requestDto.birthDate());
        appUser.setAge(age);
        appUser.setSex(requestDto.sex());
        appUser.setMbti(requestDto.mbti());

        // 대표 지역 설정 (첫번째 지역이 대표 지역)
        if (requestDto.regionCodes() != null && !requestDto.regionCodes().isEmpty()) {
            String firstRegionCode = requestDto.regionCodes().get(0);
            Optional<Region> region = accountMapper.findRegionByRegionCode(firstRegionCode);
            if (region.isPresent()) {
                appUser.setRegionCode(firstRegionCode);
            }
        }

        accountMapper.updateProfile(appUser);

        if (requestDto.regionCodes() != null && !requestDto.regionCodes().isEmpty()) {
            accountMapper.deleteUserRegion(userId);

            for (String regionCode : requestDto.regionCodes()) {
                if(accountMapper.findRegionByRegionCode(regionCode).isPresent()){
                    accountMapper.insertUserRegion(userId, regionCode);
                }
            }
        }
    }


    @Override
    public Long calculateAge(String birthDate) {
        if (birthDate == null || birthDate.length() != 8) {
            return null;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
            LocalDate birthLocalDate = LocalDate.parse(birthDate, formatter);

            LocalDate currentDate = LocalDate.now();
            return (long) Period.between(birthLocalDate, currentDate).getYears();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isProfileCompleted(Long userId) {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));

        return appUser.getNickname() != null &&
                appUser.getBirthDate() != null &&
                appUser.getSex() != null;
    }

    @Override
    @Transactional
    public void deleteAccount(Long userId) {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));

        String timestamp = String.valueOf(System.currentTimeMillis());
        String randomSuffix = timestamp.substring(Math.max(0, timestamp.length() - 6));

        if (appUser.getProfileImage() != null && !appUser.getProfileImage().isEmpty()) {
            fileService.deleteFile(appUser.getProfileImage());
        }

        appUser.setIsDeleted(true);
        appUser.setDeletedAt(LocalDateTime.now());
        appUser.setNickname("deletedUser" + randomSuffix);
        appUser.setEmail("deleted-" + userId + "-" + randomSuffix + "@anonymous.mentoss.com");

        if (appUser.getProviderId() != null && !appUser.getProviderId().isEmpty()) {
            appUser.setProviderId("deleted-" + appUser.getProviderId() + "-" + randomSuffix);
        }

        appUser.setBirthDate(null);
        appUser.setAge(null);
        appUser.setSex(null);
        appUser.setProfileImage(null);
        appUser.setMbti(null);
        appUser.setRegionCode(null);

        accountMapper.anonymizeDeletedUser(appUser);
        accountMapper.deleteUserRegion(userId);

        if (mentorMapper.existsByUserIdWithoutDeleteCheck(userId)) {
            Long mentorId = mentorMapper.findMentorIdByUserId(userId);

            lectureMapper.closeAllOpenLecturesByMentorId(mentorId);
            lectureMapper.softDeleteLecturesByMentorId(userId);
        }

        messageMapper.softDeleteMessagesByUserId(userId);

        log.info("회원 탈퇴 및 익명화 처리 완료: userId={}, anonymousNickname={}",
                userId, appUser.getNickname());
    }

    @Override
    @Transactional
    public String updateProfileImage(Long userId, MultipartFile file) throws IOException {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));

        if (appUser.getProfileImage() != null && !appUser.getProfileImage().isEmpty()) {
            fileService.deleteFile(appUser.getProfileImage());
        }

        String imageUrl = fileService.uploadFile(file, "profiles");

        accountMapper.updateProfileImage(userId, imageUrl);

        return imageUrl;
    }

    @Override
    public boolean isMentor(Long userId) {
        return mentorMapper.existsByUserId(userId);
    }

    @Override
    public MentorProfileResponseDto getMentorProfile(Long userId) {
        MentorProfile mentorProfile = mentorMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("MentorProfile", userId));

        return MentorProfileResponseDto.fromEntity(mentorProfile);
    }

    @Override
    public MentorPublicProfileDto getMentorPublicProfile(Long mentorId) {
        MentorProfile mentorProfile = mentorMapper.findByMentorId(mentorId)
                .orElseThrow(() -> new ResourceNotFoundException("MentorProfile", mentorId));

        AppUser user = accountMapper.findByUserId(mentorProfile.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", mentorProfile.getUserId()));

        List<Region> regionList = accountMapper.findRegionByUserId(user.getUserId());
        List<RegionDto> regions = regionList.stream()
                .map(r -> new RegionDto(r.getRegionCode(), r.getSido(), r.getSigungu(), r.getDong(), formatRegionDisplayName(r)))
                .toList();

        return MentorPublicProfileDto.of(user, mentorProfile, regions);
    }

    @Override
    @Transactional
    public void applyMentorProfile(Long userId, MentorProfileRequestDto requestDto) throws IOException {
        if (mentorMapper.existsByUserId(userId)) {
            throw new IllegalArgumentException("이미 멘토 신청을 하였습니다");
        }

        if (requestDto.content() == null || requestDto.content().length() < 10) {
            throw new IllegalArgumentException("자기소개는 최소 10자 이상 작성해주세요");
        }

        String appealFileUrl = null;
        if (requestDto.appealFile() != null && !requestDto.appealFile().isEmpty()) {
            appealFileUrl = fileService.uploadFile(requestDto.appealFile(), "mentor-appeals");
        }

        MentorProfile mentorProfile = MentorProfile.builder()
                .userId(userId)
                .content(requestDto.content())
                .appealFileUrl(appealFileUrl)
                .isCertified(false)
                .createdAt(LocalDateTime.now())
                .build();

        mentorMapper.insertMentorProfile(mentorProfile);
        mentorMapper.updateToMentorRole(userId);
    }

    @Override
    public void updateMentorProfile(Long userId, MentorProfileRequestDto requestDto) throws IOException {
        MentorProfile mentorProfile = mentorMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("MentorProfile", userId));

        if (requestDto.content() == null || requestDto.content().length() < 10) {
            throw new IllegalArgumentException("자기소개는 최소 10자 이상 작성해주세요");
        }

        String appealFileUrl = null;
        if (requestDto.appealFile() != null && !requestDto.appealFile().isEmpty()){
            if (mentorProfile.getAppealFileUrl() != null && !mentorProfile.getAppealFileUrl().isEmpty()) {
                fileService.deleteFile(mentorProfile.getAppealFileUrl());
            }

            appealFileUrl = fileService.uploadFile(requestDto.appealFile(), "mentor-appeals");
            mentorProfile.setAppealFileUrl(appealFileUrl);
        }

        mentorProfile.setContent(requestDto.content());
        mentorMapper.updateMentorProfile(mentorProfile);
    }

    @Override
    public MentorStatusResponseDto getMentorStatus(Long userId) {
        boolean isMentor = mentorMapper.existsByUserId(userId);
        boolean isCertified = false;

        if (isMentor) {
            MentorProfile mentorProfile = mentorMapper.findByUserId(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("MentorProfile", userId));
            isCertified = mentorProfile.getIsCertified();
        }

        return new MentorStatusResponseDto(isMentor, isCertified);
    }

    @Override
    public List<RegionDto> getUserRegions(Long userId) {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));
        List<Region> regions = accountMapper.findRegionByUserId(userId);

        return regions.stream()
                .map(region -> new RegionDto(
                        region.getRegionCode(),
                        region.getSido(),
                        region.getSigungu(),
                        region.getDong(),
                        formatRegionDisplayName(region)
                ))
                .collect(Collectors.toList());
    }

    private String formatRegionDisplayName(Region region) {
        StringBuilder sb = new StringBuilder();

        if (region.getSido() != null) {
            sb.append(region.getSido());
        }

        if (region.getSigungu() != null) {
            sb.append(" ").append(region.getSigungu());
        }

        if (region.getDong() != null) {
            sb.append(" ").append(region.getDong());
        }

        return sb.toString();
    }


    @Override
    public void updateUserRegion(Long userId, UserRegionsUpdateRequestDto regionDto) {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));

        accountMapper.deleteUserRegion(userId);

        if (regionDto.regionCodes() == null || regionDto.regionCodes().isEmpty()) {
            return;
        }

        String firstRegionCode = regionDto.regionCodes().get(0);
        Optional<Region> region = accountMapper.findRegionByRegionCode(firstRegionCode);
        if (region.isPresent()) {
            appUser.setRegionCode(firstRegionCode);
            accountMapper.updateProfile(appUser);
        }

        for(String regionCode : regionDto.regionCodes()) {
            if (accountMapper.findRegionByRegionCode(regionCode).isPresent()) {
                accountMapper.insertUserRegion(userId, regionCode);
            }
        }
    }

    @Override
    public void deleteProfileImage(Long userId) {
        AppUser appUser = accountMapper.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", userId));

        if (appUser.getProfileImage() != null && !appUser.getProfileImage().isEmpty()) {
            fileService.deleteFile(appUser.getProfileImage());
        }

        appUser.setProfileImage(null);
        accountMapper.updateProfileImage(userId, null);
    }

    @Override
    public MyLectureResponseDto getMyLecture(Long userId) {
        List<Long> lectureList = accountMapper.findLectureByUserId(userId);
        return new MyLectureResponseDto(lectureList);
    }
}
