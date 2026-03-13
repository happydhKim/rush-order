-- 서비스별 독립 데이터베이스 생성
-- MSA에서 각 서비스는 자체 DB를 소유한다 (Database per Service)

CREATE DATABASE rushorder_restaurant OWNER rushorder;
CREATE DATABASE rushorder_order OWNER rushorder;
CREATE DATABASE rushorder_payment OWNER rushorder;
CREATE DATABASE rushorder_inventory OWNER rushorder;
