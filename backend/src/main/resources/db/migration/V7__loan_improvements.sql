-- fixed amortization can be given as a monthly amount instead of a yearly percentage
ALTER TABLE loan ADD COLUMN amortization_amount NUMERIC(19, 4);
-- informational: when the loan was taken out (first payment date is start_date)
ALTER TABLE loan ADD COLUMN disbursed_on DATE;
-- informational: until when the initial rate is fixed (null = flexible)
ALTER TABLE loan ADD COLUMN rate_fixed_until DATE;
ALTER TABLE loan_rate ADD COLUMN fixed_until DATE;
