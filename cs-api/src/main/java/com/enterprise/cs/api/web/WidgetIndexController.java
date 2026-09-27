package com.enterprise.cs.api.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Widget 入口转发（坑#28，E2E-M0-1 实证修正）：Spring MVC 静态资源处理器不解析子目录
 * index.html——`GET /widget/` 直接抛 NoResourceFoundException；《08》对外口径为 /widget/，
 * 故以显式转发对齐（/widget 与 /widget/ 均受理）。资源本体 static/widget/index.html。
 */
@Controller
public class WidgetIndexController {

    @GetMapping({"/widget", "/widget/"})
    public String widgetIndex() {
        return "forward:/widget/index.html";
    }
}
