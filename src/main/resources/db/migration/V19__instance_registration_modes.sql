CREATE TABLE instance_registration_policy (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    mode varchar(16) NOT NULL CHECK (mode IN ('INVITE_ONLY','WAITLIST','OPEN')),
    bootstrap_completed boolean NOT NULL
);
INSERT INTO instance_registration_policy(singleton,mode,bootstrap_completed)
SELECT true,'INVITE_ONLY', EXISTS (SELECT 1 FROM "user");

CREATE TABLE registration_waitlist (
    id uuid PRIMARY KEY,
    email varchar(100) NOT NULL UNIQUE CHECK (email = lower(email) AND length(email) > 0),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    invite_id bigint UNIQUE REFERENCES user_invites(id) ON DELETE SET NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN
      ('PENDING','SENDING','GATEWAY_ACCEPTED','DELIVERY_FAILED','DELIVERY_UNKNOWN','DELIVERY_UNAVAILABLE')),
    attempt_id uuid,
    attempt_started_at timestamptz,
    CHECK ((status='SENDING' AND attempt_id IS NOT NULL AND attempt_started_at IS NOT NULL) OR
      (status<>'SENDING' AND attempt_id IS NULL AND attempt_started_at IS NULL))
);
