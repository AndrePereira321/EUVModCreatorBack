package com.euvmodcreator.auth;

import com.euvmodcreator.database.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "user_auth")
@Getter
@Setter
@NoArgsConstructor
public class UserAuth extends BaseEntity {

    private UUID userId;

    private String passwordHash;

}
