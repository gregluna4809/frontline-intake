INSERT INTO customer (display_name, language, veteran)
SELECT 'Demo Customer ' || lpad(n::text, 3, '0'),
       CASE n % 3 WHEN 1 THEN 'EN' WHEN 2 THEN 'ES' ELSE 'OTHER' END,
       n % 5 = 0
FROM generate_series(1, 50) AS n;

INSERT INTO resource (type, label, status)
SELECT 'PHONE', 'Phone ' || lpad(n::text, 2, '0'), 'FREE'
FROM generate_series(1, 6) AS n;

INSERT INTO resource (type, label, status)
SELECT 'COMPUTER', 'Computer ' || lpad(n::text, 2, '0'), 'FREE'
FROM generate_series(1, 8) AS n;

INSERT INTO resource (type, label, status)
SELECT 'STATION', 'Station ' || lpad(n::text, 2, '0'), 'FREE'
FROM generate_series(1, 2) AS n;
