package com.abdatytch.media_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaFileResponse {
    private String mediaId;
    private String userId;
    private String fileName;
    private String originalFileName;
    private String mimeType;
    private Long fileSize;
    private String downloadUrl;
    private String thumbnailUrl;
    private Map<String, Object> metadata;
    private String visibility;
    private List<String> tags;
    private String status;
    private LocalDateTime uploadedAt;
    private LocalDateTime updatedAt;
}
