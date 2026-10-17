-- Three logical services, each with a primary in Region A and a backup in Region B.
INSERT INTO service_nodes (service_name, region, role, active) VALUES
 ('payment-service','A','PRIMARY',TRUE), ('payment-service','B','BACKUP',FALSE),
 ('order-service',  'A','PRIMARY',TRUE), ('order-service',  'B','BACKUP',FALSE),
 ('auth-service',   'A','PRIMARY',TRUE), ('auth-service',   'B','BACKUP',FALSE);
