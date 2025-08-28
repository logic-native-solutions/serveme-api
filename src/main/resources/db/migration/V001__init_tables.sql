create table public.users
(
    id            uuid         not null,
    name          varchar(50)  not null,
    last_name     varchar(50)  not null,
    middle_name   varchar(50),
    phone_number  varchar(10)  not null,
    password      varchar(255) not null,
    date_of_birth date         not null,
    id_number     varchar(13)  not null,

    constraint users_pk primary key (id),
    constraint users_un unique (id)
);

