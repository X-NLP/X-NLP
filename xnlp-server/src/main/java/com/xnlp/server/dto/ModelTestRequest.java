package com.xnlp.server.dto;

import java.util.ArrayList;
import java.util.List;

public class ModelTestRequest {

    private String input;
    private String query;
    private List<String> documents = new ArrayList<>();

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<String> getDocuments() { return documents; }
    public void setDocuments(List<String> documents) { this.documents = documents != null ? documents : new ArrayList<>(); }
}
