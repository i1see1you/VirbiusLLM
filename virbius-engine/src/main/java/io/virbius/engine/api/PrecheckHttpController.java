package io.virbius.engine.api;

import io.virbius.engine.eval.PrecheckRequestDto;
import io.virbius.engine.eval.PrecheckResponseDto;
import io.virbius.engine.eval.PrecheckService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PrecheckHttpController {

    private final PrecheckService precheckService;

    public PrecheckHttpController(PrecheckService precheckService) {
        this.precheckService = precheckService;
    }

    @PostMapping("/v1/precheck")
    public PrecheckResponseDto precheck(@RequestBody PrecheckRequestDto request) {
        return precheckService.precheck(request);
    }
}
