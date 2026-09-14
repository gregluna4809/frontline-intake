-- One-time synthetic history for Friday 2026-09-11 (not a holiday).
-- 60 visits: 42 phones, 12 appointments, 2 computers, and one each of
-- job search, documents, benefits, and other. Rounding keeps every reason visible.
-- Appointments: 11 Mandatory / 1 Job Search Assistance; 3 Initial / 7 Follow-up
-- attended visits, plus 2 unclassified FTR registrations that never identified.
-- Repeated appointment customers provide the prior history for Follow-up.
-- Historical ticket prefixes avoid consuming or colliding with live sequences.
DO $$
DECLARE
    n INTEGER;
    appointment_number INTEGER;
    walk_in_number INTEGER;
    is_appointment BOOLEAN;
    is_ftr BOOLEAN;
    reason_code TEXT;
    resource_type TEXT;
    selected_resource BIGINT;
    selected_customer BIGINT;
    selected_staff BIGINT;
    new_visit_id BIGINT;
    final_status TEXT;
    arrived TIMESTAMPTZ;
    finished TIMESTAMPTZ;
BEGIN
    FOR n IN 1..60 LOOP
        is_appointment := n % 5 = 0;
        appointment_number := n / 5;
        walk_in_number := n - n / 5;
        is_ftr := is_appointment AND appointment_number > 10;
        reason_code := CASE WHEN is_appointment THEN NULL
            WHEN walk_in_number <= 42 THEN 'PHONE_ACCESS'
            WHEN walk_in_number <= 44 THEN 'COMPUTER_ACCESS'
            WHEN walk_in_number = 45 THEN 'JOB_SEARCH'
            WHEN walk_in_number = 46 THEN 'DOCUMENT_SERVICES'
            WHEN walk_in_number = 47 THEN 'BENEFITS_ASSISTANCE'
            ELSE 'OTHER' END;
        resource_type := CASE WHEN is_appointment THEN NULL
            WHEN reason_code = 'PHONE_ACCESS' THEN 'PHONE'
            WHEN reason_code IN ('COMPUTER_ACCESS', 'JOB_SEARCH') THEN 'COMPUTER'
            ELSE 'STATION' END;
        selected_resource := NULL;
        IF NOT is_appointment THEN
            SELECT id INTO selected_resource FROM resource WHERE type = resource_type
            ORDER BY label, id LIMIT 1 OFFSET (n % CASE resource_type WHEN 'PHONE' THEN 6 WHEN 'COMPUTER' THEN 8 ELSE 2 END);
        END IF;
        selected_customer := CASE WHEN is_appointment THEN 1 + (appointment_number - 1) % 3 ELSE 10 + (n - 1) % 40 END;
        selected_staff := CASE WHEN is_appointment AND NOT is_ftr THEN 1 + (appointment_number - 1) % 4 ELSE NULL END;
        final_status := CASE WHEN n % 13 = 0 THEN 'LEFT' ELSE 'CLOSED' END;
        arrived := TIMESTAMPTZ '2026-09-11 08:35:00 America/New_York' + (n - 1) * INTERVAL '7 minutes';
        finished := CASE WHEN is_ftr THEN TIMESTAMPTZ '2026-09-11 16:40:00 America/New_York'
            ELSE arrived + CASE WHEN resource_type = 'STATION' THEN INTERVAL '12 minutes' ELSE INTERVAL '20 minutes' END END;

        INSERT INTO visit (ticket, visit_type, reason, appointment_category, appointment_type,
            appointment_status, assigned_staff_id, status, customer_id, resource_id,
            created_at, closed_at, version, business_date)
        VALUES ((CASE WHEN is_appointment THEN 'A-HIST-' ELSE 'B-HIST-' END) || lpad(n::text, 4, '0'),
            CASE WHEN is_appointment THEN 'APPOINTMENT' ELSE 'WALK_IN' END, reason_code,
            CASE WHEN is_appointment THEN CASE WHEN appointment_number = 10 THEN 'JOB_SEARCH_ASSISTANCE' ELSE 'MANDATORY' END ELSE NULL END,
            CASE WHEN NOT is_appointment OR is_ftr THEN NULL WHEN appointment_number <= 3 THEN 'INITIAL' ELSE 'FOLLOWUP' END,
            CASE WHEN is_ftr THEN 'NOT_ATTENDED_FTR' WHEN is_appointment THEN 'SCHEDULED' ELSE NULL END,
            selected_staff, final_status, selected_customer, selected_resource,
            arrived, finished, CASE WHEN is_ftr THEN 1 ELSE 3 END, DATE '2026-09-11')
        RETURNING id INTO new_visit_id;

        INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at, note)
        VALUES (new_visit_id, NULL, 'CHECKED_IN', CASE WHEN is_ftr THEN 'SYSTEM' ELSE 'KIOSK' END, arrived,
            CASE WHEN is_ftr THEN 'Synthetic historical appointment registration; customer did not attend.'
                ELSE 'Synthetic historical check-in.' END);
        IF NOT is_ftr THEN
            INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at, note)
            VALUES (new_visit_id, 'CHECKED_IN', 'IDENTIFIED', 'FRONT_DESK', arrived + INTERVAL '2 minutes',
                'Synthetic historical customer attachment.'),
                (new_visit_id, 'IDENTIFIED', CASE WHEN is_appointment THEN 'WAITING_STAFF' ELSE 'AT_RESOURCE' END,
                'FRONT_DESK', arrived + INTERVAL '5 minutes', 'Synthetic historical routing.');
        END IF;
        INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at, note)
        VALUES (new_visit_id, CASE WHEN is_ftr THEN 'CHECKED_IN' WHEN is_appointment THEN 'WAITING_STAFF' ELSE 'AT_RESOURCE' END,
            final_status, CASE WHEN is_ftr THEN 'SYSTEM' WHEN is_appointment THEN 'APPOINTMENTS' ELSE 'RESOURCE_ROOM' END,
            finished, CASE WHEN is_ftr THEN 'Synthetic historical FTR: NOT_ATTENDED_FTR; appointment closed.'
                ELSE 'Synthetic historical visit completed; any resource released.' END);
    END LOOP;
END $$;

-- Match the Step 9 JSON shape. All seeded occupants have departed at close.
-- Do not reset or change any live resource, call-out, rollover marker, or ticket sequence.
INSERT INTO business_day_snapshot (business_date, captured_at, payload)
SELECT DATE '2026-09-11', TIMESTAMPTZ '2026-09-11 16:45:00 America/New_York', jsonb_build_object(
    'businessDate', '2026-09-11',
    'visits', (SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'id', v.id, 'ticket', v.ticket, 'visitType', v.visit_type, 'reason', v.reason,
        'appointmentCategory', v.appointment_category, 'appointmentType', v.appointment_type,
        'appointmentStatus', v.appointment_status, 'assignedStaffId', v.assigned_staff_id,
        'status', v.status, 'customerId', v.customer_id, 'resourceId', v.resource_id,
        'createdAt', to_char(v.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'closedAt', CASE WHEN v.closed_at IS NULL THEN NULL ELSE to_char(v.closed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') END,
        'version', v.version, 'previousVisitId', v.previous_visit_id, 'businessDate', v.business_date,
        'customer', CASE WHEN c.id IS NULL THEN NULL ELSE jsonb_build_object('id', c.id, 'displayName', c.display_name,
            'language', c.language, 'veteran', c.veteran) END,
        'waitingSince', NULL,
        'requiredResourceType', CASE WHEN v.visit_type = 'APPOINTMENT' THEN NULL
            WHEN v.reason = 'PHONE_ACCESS' THEN 'PHONE'
            WHEN v.reason IN ('COMPUTER_ACCESS', 'JOB_SEARCH') THEN 'COMPUTER' ELSE 'STATION' END
    ) ORDER BY v.created_at, v.id), '[]'::jsonb)
        FROM visit v LEFT JOIN customer c ON c.id = v.customer_id WHERE v.business_date = DATE '2026-09-11'),
    'resources', (SELECT jsonb_agg(jsonb_build_object('id', r.id, 'type', r.type, 'label', r.label,
        'status', 'FREE', 'visitId', NULL, 'ticket', NULL, 'customer', NULL, 'visitVersion', NULL, 'assignedAt', NULL)
        ORDER BY r.type, r.label, r.id) FROM resource r),
    'staff', (SELECT jsonb_agg(jsonb_build_object('id', s.id, 'displayName', s.display_name, 'role', s.role)
        ORDER BY s.role, s.display_name, s.id) FROM staff s),
    'coverage', (SELECT jsonb_agg(jsonb_build_object('staffId', s.id, 'displayName', s.display_name,
        'dutyType', CASE WHEN c.id IS NOT NULL THEN 'RESOURCE_ROOM' ELSE r.duty_type END) ORDER BY s.display_name, s.id)
        FROM staff s JOIN staff_rotation r ON r.staff_id = s.id AND r.day_of_week = 5
        LEFT JOIN call_out c ON c.staff_id = s.id AND c.date = DATE '2026-09-11' WHERE s.role = 'LSR'),
    'callOuts', (SELECT COALESCE(jsonb_agg(jsonb_build_object('id', c.id, 'staffId', c.staff_id, 'date', c.date,
        'reason', c.reason, 'createdAt', to_char(c.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
        ORDER BY c.staff_id, c.id), '[]'::jsonb) FROM call_out c WHERE c.date = DATE '2026-09-11')
)
ON CONFLICT (business_date) DO UPDATE SET payload = jsonb_set(business_day_snapshot.payload, '{visits}', (
    -- Preserve existing frozen entries and metadata, adding only the new historical visits.
    SELECT jsonb_agg(item ORDER BY item->>'createdAt', (item->>'id')::bigint)
    FROM (
        SELECT item FROM jsonb_array_elements(business_day_snapshot.payload->'visits') item
        UNION ALL
        SELECT item FROM jsonb_array_elements(EXCLUDED.payload->'visits') item
        WHERE item->>'ticket' ~ '^[AB]-HIST-[0-9]{4}$'
        AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(business_day_snapshot.payload->'visits') old
            WHERE old->>'id' = item->>'id')
    ) combined
));
