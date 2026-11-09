package com.moviemux.movie.domain.model;
import java.util.ArrayList;
import java.util.UUID;
/** Visibility is a response projection. It never erases scores in the persisted aggregate. */
public final class MovieVisibility {
 private MovieVisibility(){ }
 public static Movie project(Movie source, UUID viewer, boolean order) {
  Movie result=source.toBuilder().build();
  Movie header=source.toBuilder().notes(null).genres(null).movieDiscord(null).build();
  if(source.getMovieDiscord()!=null)result.setMovieDiscord(new MovieDiscord(source.getMovieDiscord().getId(),source.getMovieDiscord().getMessageId(),header));
  if(source.getGenres()!=null)result.setGenres(source.getGenres().stream().map(g->MovieGenre.builder().id(g.getId()).name(g.getName()).tmdbId(g.getTmdbId()).build()).toList());
  if(source.getNotes()!=null){
   var notes=new ArrayList<MovieNote>();
   for(var note:source.getNotes()){
    notes.add(new MovieNote(note.getId(),note.getNote(),note.getCreatedAt(),note.getUpdatedAt(),note.getUser(),header));
   }
   if(order)notes.sort(source.isShowNotes()?MovieNote.comparator():MovieNote.comparatorAlphabetical());
   if(!source.isShowNotes())for(var note:notes)if(!note.getUser().getId().equals(viewer))note.setNote(null);
   result.setNotes(notes);
  }
  return result;
 }
}
