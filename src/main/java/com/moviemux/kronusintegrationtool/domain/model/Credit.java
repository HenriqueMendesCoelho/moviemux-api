package com.moviemux.kronusintegrationtool.domain.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Credit {

  List<Cast> cast;
  List<Cast> crew;

  @Getter
  @Setter
  @Builder
  @AllArgsConstructor
  @NoArgsConstructor
  public static class Cast {

    private String knownForDepartment;
    private String name;
    private String originalName;
    private String profilePath;
    private String character;
    private String department;
    private String job;
  }
}
