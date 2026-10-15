package vn.edu.huit.finance.core.Dto;

import java.util.List;
import java.util.Map;

public class SegmentResponse {
    private List<Map<String, String>> segments;

    public List<Map<String, String>> getSegments() {
        return segments;
    }

    public void setSegments(List<Map<String, String>> segments) {
        this.segments = segments;
    }
}
