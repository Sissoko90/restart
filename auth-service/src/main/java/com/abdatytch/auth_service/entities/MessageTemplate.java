package com.abdatytch.auth_service.entities;

import com.abdatytch.auth_service.enums.TemplateType;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Table("message_templates")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageTemplate extends BaseEntity {
    @Column("template_type")
    private TemplateType templateType;
    
    @Column("subject")
    private String subject;
    
    @Column("content")
    private String content;
    
    @Column("description")
    private String description;
    
    @Column("is_active")
    @Builder.Default
    private Boolean isActive = true;
    
    @Column("variables")
    private String variables;
}
