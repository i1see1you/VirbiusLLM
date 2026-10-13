package io.virbius.control.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 运营台入口：统一指向 Vue3 SPA（`static/ui/index.html`，hash 路由）。 */
@Controller
public class OpsUiController {

    @GetMapping({"/ui", "/ui/", "/ops"})
    public String console() {
        return "redirect:/ui/index.html";
    }
}
