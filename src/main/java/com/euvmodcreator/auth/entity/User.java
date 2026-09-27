package com.euvmodcreator.auth.entity;

import com.euvmodcreator.database.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    private String username;

    private String displayName;

    private String bio;

    private String steamUrl;

    private String paradoxForumUrl;

    private String discordUrl;

}
