package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreationDTO {
    private String authUserId;
    private String email;
    private String phoneNumber;
    private String countryCode;
    private String firstName;
    private String lastName;
    private UserRole role;
    private Set<UserRole> roles;
    private UserRole primaryRole;
    private LocalDate dateOfBirth;
    private String address;
    private String bio;
    private String profilePicture;
}
