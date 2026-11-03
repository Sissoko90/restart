package com.abdatytch.media_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaUploadResponse {
    private String mediaId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private String downloadUrl;
    private String thumbnailUrl;
    private String message;
}
