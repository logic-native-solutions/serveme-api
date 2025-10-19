INSERT INTO roles (id, name) VALUES ('d6298782-5042-4115-b653-0bc4d76b3aa3', 'CLIENT');

INSERT INTO users (id,role_id,id_number,first_name,last_name,email,phone_number,date_of_birth,gender,password)
            VALUES (
                    'a0433b25-a692-471b-905f-14ce408f3d63',
                    'd6298782-5042-4115-b653-0bc4d76b3aa3',
                    '222222222222',
                    'Phumudzo',
                    'Maphari',
                    'test@gmail.com',
                    '0712345678',
                    '1990-01-01',
                    'Male',
                    '$2a$10$200Z6ZZbp3RAEXoaWcMAeusfMhtf3h7YFn6g245tfuBqOqmq4Y4e'
                   );

INSERT INTO public.users (id, role_id, id_number, first_name, last_name, email, phone_number, date_of_birth, gender, verified, password) VALUES ('f64912ba-caeb-4efe-befd-2c17bbcd4535', 'd6298782-5042-4115-b653-0bc4d76b3aa3', '4637484938474', 'Vinnie', 'Maphari', 'vinniedebassist@gmail.com', '72 010 5615', '2000-01-01', 'Male', false, '$2a$10$WVagmO60LzVnRIQG/MsTqOTVR.lxoFXXXE1u0ULJunBfcBqmVYaL.');



