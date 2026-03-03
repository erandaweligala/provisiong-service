package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedGroupUsersResponse {

    @JsonProperty("group_id")
    private String groupId;

    @JsonProperty("page")
    private Integer page;

    @JsonProperty("page_size")
    private Integer pageSize;

    @JsonProperty("total_users")
    private Long totalUsers;

    @JsonProperty("users")
    private List<GroupUserInfo> users;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GroupUserInfo {

        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("status")
        private Integer status;
    }
}
