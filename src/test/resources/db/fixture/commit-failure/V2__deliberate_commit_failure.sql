create table commit_parent (id integer primary key);
create table commit_child (id integer references commit_parent(id) deferrable initially deferred);
insert into commit_child values (999);
