package vn.edu.huit.finance.core.Controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @GetMapping("/login")
    public String redirectLogin() {
        return "redirect:/admin/login";
    }
}
