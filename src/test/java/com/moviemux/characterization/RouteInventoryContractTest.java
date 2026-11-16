package com.moviemux.characterization;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteInventoryContractTest {
    @Test void allControllerRoutesMethodsMediaTypesAndAdministrativeGuardsMatchBaseline() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> routes = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents("com.moviemux")) {
            if (!bean.getBeanClassName().contains(".application.spring.controller.")) continue;
            var type = Class.forName(bean.getBeanClassName());
            var root = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            for (var method : type.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                var guard = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                for (String path : paths) {
                    routes.add(Arrays.toString(mapping.method()) + " " + (root.path()[0] + "/" + path).replaceAll("/+", "/").replaceAll("/$", "")
                            + " consumes=" + Arrays.toString(mapping.consumes()) + " guard=" + (guard == null ? "" : guard.value()));
                }
            }
        }
        try (var fixture = getClass().getResourceAsStream("/characterization/routes.txt")) {
            assertThat(fixture).isNotNull();
            var expected = new String(fixture.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertThat(routes.stream().sorted().toList()).containsExactlyElementsOf(expected);
        }
    }
}
