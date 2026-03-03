package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupUserResponse {
    private String groupId;
    private Integer page;
    private Integer pageSize;
    private Long totalUsers;
    private List<GroupUserInfo> users;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GroupUserInfo {
        private String username;
        private Integer status;
    }
}