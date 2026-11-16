package com.moviemux.discord.adapter.repository.rest.dto;

import java.time.OffsetDateTime;
import java.util.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EmbedRequestDto {

  public String title;
  public Image image;
  public int color;
  public String url;
  public Footer footer;
  public List<Field> fields;
  public OffsetDateTime timestamp;

  @Data
  @AllArgsConstructor
  public static class Field {
    public String name;
    public String value;
    public boolean inline;

    public static Comparator<Field> comparator() {
      return Comparator.comparingInt(Field::getValueInt).reversed().thenComparing(Field::getName);
    }

    public int getValueInt() {
      return Integer.parseInt(this.getValue());
    }
  }

  @Data
  @AllArgsConstructor
  public static class Footer {
    public String text;
  }

  @Data
  @AllArgsConstructor
  public static class Image {
    public String url;
  }
}
