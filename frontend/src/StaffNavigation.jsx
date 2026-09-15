import { landingPage, logout, navigate, roleLabels, rolePaths } from './auth.js';

const labels = { '/front-desk': 'Front desk', '/supervisor': 'Supervisor',
  '/my-queue': 'My Queue', '/resource-room': 'Resource room' };

export function Brand({ path = '/kiosk' }) {
  return <a className="brand" href={path} onClick={event => {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault(); navigate(path);
  }}><span className="brand-mark">F</span><span>Frontline<small>Career Center</small></span></a>;
}

export default function StaffNavigation({ auth, path, kiosk = false }) {
  return <>
    <header className="topbar">
      <Brand path={kiosk ? '/kiosk' : auth ? landingPage(auth.role) : '/login'} />
      {auth ? <div className="staff-identity"><div><strong>{auth.displayName}</strong><small>{roleLabels[auth.role]}</small></div>
        <button className="secondary" onClick={logout}>Logout</button>
      </div> : <a className="staff-login" href="/login" onClick={event => {
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault(); navigate('/login');
      }}>Staff login</a>}
    </header>
    {auth && !kiosk && auth.role !== 'ADMINISTRATOR' && <nav className="staff-nav" aria-label="Staff navigation">
      {rolePaths[auth.role].map(href => <a key={href} href={href} aria-current={path === href ? 'page' : undefined}
        onClick={event => {
          if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
          event.preventDefault(); navigate(href);
        }}>{labels[href]}</a>)}
    </nav>}
  </>;
}
