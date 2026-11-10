package com.account0.learn;

import com.account0.common.ApiException;
import com.account0.llm.LlmException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learn")
public class LearnController {
    private final LearnService svc;
    public LearnController(LearnService svc) { this.svc = svc; }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> b) { try { return svc.ask(b.get("question")); } catch (LlmException e) { throw ApiException.bad(e.getMessage()); } }
    @GetMapping("/history") public List<Map<String, Object>> history() { return svc.history(); }
    @GetMapping("/search") public List<Map<String, Object>> search(@RequestParam String q) { return svc.search(q); }
    @GetMapping("/library") public List<Map<String, Object>> library() { return svc.library(); }
    @GetMapping("/why") public Map<String, Object> why() { return svc.why(); }
    @GetMapping("/walkthrough/{topic}") public Map<String, Object> walk(@PathVariable String topic, @RequestParam String period) { return svc.walkthrough(topic, period); }
    @PostMapping("/walkthrough/{topic}/narrate")
    public Map<String, Object> narrate(@PathVariable String topic, @RequestParam String period) { try { return Map.of("narration", svc.narrate(topic, period)); } catch (LlmException e) { throw ApiException.bad(e.getMessage()); } }
    @GetMapping("/diy/{period}") public Map<String, Object> diy(@PathVariable String period) { return svc.diy(period); }
    @PostMapping("/diy/{period}/{key}") public Map<String, Object> diyMark(@PathVariable String period, @PathVariable String key, @RequestBody Map<String, Object> b) { return svc.diyMark(period, key, !Boolean.FALSE.equals(b.get("done"))); }
    @GetMapping("/diy-readiness") public Map<String, Object> readiness() { return svc.readiness(); }
}
