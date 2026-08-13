import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { RequestFailed } from '../../api/client'
import { useProfile, useSaveProfile, useSession } from '../../api/queries'
import { PageHeader } from '../../components/common'

const ENTITY_TYPES = [
  'Sole Proprietorship',
  'Partnership',
  'LLC',
  'S Corporation',
  'C Corporation',
  'Non-profit',
]

/**
 * The company profile, pre-filled from whatever Acme already holds.
 *
 * The tax ID is the one field that behaves differently: it goes in once and only
 * its last four digits ever come back. That is not a UI convention — the value
 * is encrypted at rest and has no read path — so the field explains itself
 * rather than looking like a bug.
 */
export default function PortalProfile() {
  const session = useSession()
  const navigate = useNavigate()
  const supplierId = session.data?.supplierId ?? undefined
  const profile = useProfile(supplierId)
  const save = useSaveProfile(supplierId ?? '')

  const [form, setForm] = useState({
    legalName: '',
    dbaName: '',
    entityType: '',
    taxId: '',
    addressLine1: '',
    addressLine2: '',
    city: '',
    state: '',
    postalCode: '',
    primaryContactName: '',
    primaryContactEmail: '',
    primaryContactPhone: '',
  })

  useEffect(() => {
    if (!profile.data) return
    setForm({
      legalName: profile.data.legalName ?? '',
      dbaName: profile.data.dbaName ?? '',
      entityType: profile.data.entityType ?? '',
      taxId: '',
      addressLine1: profile.data.addressLine1 ?? '',
      addressLine2: profile.data.addressLine2 ?? '',
      city: profile.data.city ?? '',
      state: profile.data.state ?? '',
      postalCode: profile.data.postalCode ?? '',
      primaryContactName: profile.data.primaryContactName ?? '',
      primaryContactEmail: profile.data.primaryContactEmail ?? '',
      primaryContactPhone: profile.data.primaryContactPhone ?? '',
    })
  }, [profile.data])

  if (profile.isPending || !supplierId) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  const failure = save.error instanceof RequestFailed ? save.error.message : null
  const storedTaxId = profile.data?.taxIdLast4

  function field(name: keyof typeof form) {
    return {
      value: form[name],
      onChange: (event: React.ChangeEvent<HTMLInputElement>) =>
        setForm((current) => ({ ...current, [name]: event.target.value })),
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    await save.mutateAsync({ ...form, taxId: form.taxId ? form.taxId : null })
    navigate('/portal')
  }

  return (
    <Box sx={{ maxWidth: 720 }}>
      <PageHeader
        title="Company profile"
        description="This is who Acme contracts with. Your documents are checked against it, so the legal name here should match your W-9 exactly."
      />

      <form onSubmit={submit}>
        <Stack spacing={3}>
          {failure ? <Alert severity="error">{failure}</Alert> : null}

          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                The company
              </Typography>
              <Stack spacing={2.5}>
                <TextField label="Legal name" required fullWidth {...field('legalName')} />
                <TextField
                  label="Doing business as"
                  fullWidth
                  helperText="Only if you trade under a different name."
                  {...field('dbaName')}
                />
                <TextField label="Entity type" select fullWidth {...field('entityType')}>
                  {ENTITY_TYPES.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  label="Tax ID (EIN or SSN)"
                  fullWidth
                  {...field('taxId')}
                  placeholder={storedTaxId ? `On file, ending ${storedTaxId}` : '12-3456789'}
                  helperText={
                    storedTaxId
                      ? `We hold a tax ID ending ${storedTaxId}. It is encrypted, and we only ever show the last four digits. Leave this blank unless it has changed.`
                      : 'Nine digits. Encrypted when stored; only the last four are ever shown again.'
                  }
                />
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                Address
              </Typography>
              <Stack spacing={2.5}>
                <TextField label="Street address" fullWidth {...field('addressLine1')} />
                <TextField label="Suite, floor, unit" fullWidth {...field('addressLine2')} />
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <TextField label="City" fullWidth {...field('city')} />
                  <TextField label="State" fullWidth {...field('state')} />
                  <TextField label="ZIP code" fullWidth {...field('postalCode')} />
                </Stack>
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                Who we should contact
              </Typography>
              <Stack spacing={2.5}>
                <TextField label="Name" fullWidth {...field('primaryContactName')} />
                <TextField label="Email address" type="email" fullWidth {...field('primaryContactEmail')} />
                <TextField label="Phone" fullWidth {...field('primaryContactPhone')} />
              </Stack>
            </CardContent>
          </Card>

          <Stack direction="row" spacing={1.5}>
            <Button type="submit" variant="contained" size="large" disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Save profile'}
            </Button>
            <Button color="inherit" onClick={() => navigate('/portal')}>
              Back to onboarding
            </Button>
          </Stack>
        </Stack>
      </form>
    </Box>
  )
}
