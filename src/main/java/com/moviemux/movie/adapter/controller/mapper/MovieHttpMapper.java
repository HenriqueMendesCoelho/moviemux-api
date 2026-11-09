package com.moviemux.movie.adapter.controller.mapper;
import com.moviemux.movie.adapter.controller.dto.*;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.command.*;
import org.springframework.data.domain.*;
import java.util.*;
import java.time.*;
public final class MovieHttpMapper {
 private MovieHttpMapper(){ }
 public static Movie request(MovieRequestDto source){
  Movie result=new Movie();
  result.setPortugueseTitle(source.getPortugueseTitle());
  result.setEnglishTitle(source.getEnglishTitle());
  result.setOriginalTitle(source.getOriginalTitle());
  result.setDirector(source.getDirector());
  result.setUrlImage(source.getUrlImage());
  result.setPortugueseUrlTrailer(source.getPortugueseUrlTrailer());
  result.setEnglishUrlTrailer(source.getEnglishUrlTrailer());
  result.setDescription(source.getDescription());
  result.setReleaseDate(source.getReleaseDate());
  result.setTmdbId(source.getTmdbId());
  result.setImdbId(source.getImdbId());
  result.setRuntime(source.getRuntime());
  result.setShowNotes(source.isShowNotes());
  result.setGenres(source.getGenres().stream().map(id->MovieGenre.builder().id(id).build()).toList());return result;
 }
 public static CreateMovieCommand create(MovieRequestDto source,UUID actor){return new CreateMovieCommand(request(source),actor);}
 public static UpdateMovieCommand update(MovieRequestDto source,UUID id,String email){return new UpdateMovieCommand(request(source),id,email);}
 public static MovieResponseDto movie(Movie source){
  MovieResponseDto result=new MovieResponseDto();
  result.setId(source.getId());
  result.setPortugueseTitle(source.getPortugueseTitle());
  result.setEnglishTitle(source.getEnglishTitle());
  result.setOriginalTitle(source.getOriginalTitle());
  result.setDirector(source.getDirector());
  result.setUrlImage(source.getUrlImage());
  result.setPortugueseUrlTrailer(source.getPortugueseUrlTrailer());
  result.setEnglishUrlTrailer(source.getEnglishUrlTrailer());
  result.setDescription(source.getDescription());
  result.setReleaseDate(source.getReleaseDate());
  result.setTmdbId(source.getTmdbId());
  result.setImdbId(source.getImdbId());
  result.setRuntime(source.getRuntime());
  result.setShowNotes(source.isShowNotes());
  result.setCreatedAt(source.getCreatedAt());
  result.setUpdatedAt(source.getUpdatedAt());
  if(source.getUser()!=null){result.setUserId(source.getUser().id());result.setUserName(source.getUser().name());}
  if(source.getNotes()!=null)result.setNotes(source.getNotes().stream().map(MovieHttpMapper::note).toList());
  result.setGenres(source.getGenres().stream().map(MovieHttpMapper::genre).toList());
  result.setTimeSinceCreation(Duration.between(source.getCreatedAt(),OffsetDateTime.now()).getSeconds());
  return result;
 }
 public static MovieNoteResponseDto note(MovieNote source){return new MovieNoteResponseDto(source.getNote(),source.getCreatedAt(),source.getUpdatedAt(),new MovieNoteUserResponseDto(source.getUser().id(),source.getUser().name()));}
 public static MovieGenreResponseDto genre(MovieGenre source){return new MovieGenreResponseDto(source.getId(),source.getName(),source.getTmdbId());}
 public static MovieQuery query(String title,String genres,String sortJoin,Pageable pageable){
  return new MovieQuery(title,genres,sortJoin,pageable.isPaged()?pageable.getPageNumber():0,pageable.isPaged()?pageable.getPageSize():0,pageable.isUnpaged(),pageable.getSort().stream().map(o->new MovieQuery.Order(o.getProperty(),o.isAscending(),o.isIgnoreCase(),MovieQuery.NullHandling.valueOf(o.getNullHandling().name()))).toList());
 }
 public static Page<MovieResponseDto> page(MoviePage result,Pageable pageable){return new PageImpl<>(result.content().stream().map(MovieHttpMapper::movie).toList(),pageable,result.totalElements());}
}
