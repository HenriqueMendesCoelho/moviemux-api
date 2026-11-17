-- Expand only the V2 capability check. No grants are assigned, removed or changed.
alter table ranking_membership_grant drop constraint ranking_membership_grant_capability_check;
alter table ranking_membership_grant add constraint ranking_membership_grant_capability_check
    check (capability in ('READ', 'RATE_OWN', 'ADD_ENTRY', 'REMOVE_OWN_ENTRY',
        'REMOVE_ENTRY', 'INVITE_MEMBER', 'MANAGE_MEMBERS'));
