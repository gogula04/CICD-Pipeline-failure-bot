package com.pipeline.intelligence_bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabJob {

    private Long id;
    private String name;
    private String stage;
    private String status;

    private String started_at;
    private String finished_at;
    private String created_at;

    private Boolean allow_failure;
    private String when;

    public GitLabJob() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStarted_at() { return started_at; }
    public void setStarted_at(String started_at) { this.started_at = started_at; }

    public String getFinished_at() { return finished_at; }
    public void setFinished_at(String finished_at) { this.finished_at = finished_at; }

    public String getCreated_at() { return created_at; }
    public void setCreated_at(String created_at) { this.created_at = created_at; }

    public Boolean getAllow_failure() { return allow_failure; }
    public void setAllow_failure(Boolean allow_failure) { this.allow_failure = allow_failure; }

    public String getWhen() { return when; }
    public void setWhen(String when) { this.when = when; }
}