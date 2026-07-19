import React, { useState, useEffect } from 'react';
import Sidebar from './components/Sidebar';
import TopBar from './components/TopBar';
import Login from './pages/Login';
import Inventory from './pages/Inventory';
import Shelves from './pages/Shelves';
import Orders from './pages/Orders';
import UserRegister from './pages/UserRegister';

export default function App() {
  const [currentUser, setCurrentUser] = useState<{ username: string; nickname: string; role: string } | null>(null);
  const [activeTab, setActiveTab] = useState('inventory');
  const [alerts, setAlerts] = useState({
    unscanned_checkouts: [] as any[],
    expired_chemicals: [] as any[],
    co_storage_warnings: [] as any[]
  });

  const fetchAlerts = async () => {
    try {
      const response = await fetch('/api/chemicals/alerts');
      if (response.ok) {
        const data = await response.json();
        setAlerts(data);
      }
    } catch (err) {
      console.error("Failed to fetch warnings/alerts:", err);
    }
  };

  useEffect(() => {
    // If logged in, fetch alerts periodically
    if (currentUser) {
      fetchAlerts();
      const interval = setInterval(fetchAlerts, 5000);
      return () => clearInterval(interval);
    }
  }, [currentUser]);

  const handleLoginSuccess = (user: { username: string; nickname: string; role: string }) => {
    setCurrentUser(user);
    // Persist login state
    localStorage.setItem('currentUser', JSON.stringify(user));
  };

  const handleLogout = () => {
    setCurrentUser(null);
    localStorage.removeItem('currentUser');
  };

  // Restore login session
  useEffect(() => {
    const savedUser = localStorage.getItem('currentUser');
    if (savedUser) {
      try {
        setCurrentUser(JSON.parse(savedUser));
      } catch (err) {
        localStorage.removeItem('currentUser');
      }
    }
  }, []);

  if (!currentUser) {
    return <Login onLoginSuccess={handleLoginSuccess} />;
  }

  // Get active tab details
  const getTabDetails = () => {
    switch (activeTab) {
      case 'inventory':
        return {
          title: '재고 관리',
          subtitle: '실시간 화학 시약 현황, 무게 잔량 및 안전 상태 모니터링',
          component: <Inventory alerts={alerts} refreshAlerts={fetchAlerts} />
        };
      case 'shelves':
        return {
          title: '선반 관리',
          subtitle: '구역별 스마트 선반(행/열) 상세 관리 및 신규 디바이스 매핑',
          component: <Shelves />
        };
      case 'orders':
        return {
          title: '주문 관리',
          subtitle: '잔량 부족 후보 시약 발주 및 주문 승인 프로세스',
          component: <Orders />
        };
      case 'users':
        return {
          title: '사용자 등록',
          subtitle: '연구원 계정 생성 및 권한 역할 배정',
          component: <UserRegister />
        };
      default:
        return {
          title: '대시보드',
          subtitle: '스마트 시약장 대시보드',
          component: <Inventory alerts={alerts} refreshAlerts={fetchAlerts} />
        };
    }
  };

  const currentTab = getTabDetails();

  return (
    <div className="app-container">
      <Sidebar 
        activeTab={activeTab} 
        setActiveTab={setActiveTab} 
        currentUser={currentUser}
        onLogout={handleLogout}
      />
      <div className="main-content">
        <TopBar 
          title={currentTab.title} 
          subtitle={currentTab.subtitle} 
          alerts={alerts}
        />
        {currentTab.component}
      </div>
    </div>
  );
}
