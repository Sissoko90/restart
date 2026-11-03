package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.enums.TemplateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageTemplateRequest {
    
    @NotNull(message = AppConstants.TEMPLATE_TYPE_REQUIRED_MESSAGE)
    private TemplateType templateType;
    
    @Size(max = 200, message = AppConstants.TEMPLATE_SUBJECT_TOO_LONG_MESSAGE)
    private String subject;
    
    @NotBlank(message = AppConstants.TEMPLATE_CONTENT_REQUIRED_MESSAGE)
    private String content;
    
    @Size(max = 500, message = AppConstants.TEMPLATE_DESCRIPTION_TOO_LONG_MESSAGE)
    private String description;
    
    private Boolean isActive;
    
    @Size(max = 500, message = AppConstants.TEMPLATE_VARIABLES_TOO_LONG_MESSAGE)
    private String variables;
}
