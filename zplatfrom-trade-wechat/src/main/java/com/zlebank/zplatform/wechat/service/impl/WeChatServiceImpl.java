/* 
 * WeChatServiceImpl.java  
 * 
 * version TODO
 *
 * 2016年5月25日 
 * 
 * Copyright (c) 2016,zlebank.All rights reserved.
 * 
 */
package com.zlebank.zplatform.wechat.service.impl;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.zlebank.zplatform.acc.service.AccEntryService;
import com.zlebank.zplatform.commons.dao.pojo.BusiTypeEnum;
import com.zlebank.zplatform.commons.utils.DateUtil;
import com.zlebank.zplatform.commons.utils.RSAUtils;
import com.zlebank.zplatform.commons.utils.StringUtil;
import com.zlebank.zplatform.commons.utils.security.AESHelper;
import com.zlebank.zplatform.commons.utils.security.AESUtil;
import com.zlebank.zplatform.commons.utils.security.RSAHelper;
import com.zlebank.zplatform.member.bean.MerchMK;
import com.zlebank.zplatform.member.bean.enums.TerminalAccessType;
import com.zlebank.zplatform.member.service.CoopInstiService;
import com.zlebank.zplatform.member.service.MerchMKService;
import com.zlebank.zplatform.trade.adapter.accounting.IAccounting;
import com.zlebank.zplatform.trade.bean.AppPartyBean;
import com.zlebank.zplatform.trade.bean.PayPartyBean;
import com.zlebank.zplatform.trade.bean.ResultBean;
import com.zlebank.zplatform.trade.bean.TradeBean;
import com.zlebank.zplatform.trade.bean.enums.ChannelEnmu;
import com.zlebank.zplatform.trade.bean.enums.ChnlTypeEnum;
import com.zlebank.zplatform.trade.bean.enums.OrderStatusEnum;
import com.zlebank.zplatform.trade.bean.enums.RefundStatusEnum;
import com.zlebank.zplatform.trade.bean.enums.TradeStatFlagEnum;
import com.zlebank.zplatform.trade.bean.gateway.AnonOrderAsynRespBean;
import com.zlebank.zplatform.trade.bean.gateway.OrderAsynRespBean;
import com.zlebank.zplatform.trade.dao.ITxnsOrderinfoDAO;
import com.zlebank.zplatform.trade.dao.RspmsgDAO;
import com.zlebank.zplatform.trade.factory.AccountingAdapterFactory;
import com.zlebank.zplatform.trade.model.PojoRspmsg;
import com.zlebank.zplatform.trade.model.TxnsLogModel;
import com.zlebank.zplatform.trade.model.TxnsOrderinfoModel;
import com.zlebank.zplatform.trade.model.TxnsRefundModel;
import com.zlebank.zplatform.trade.service.ITxnsLogService;
import com.zlebank.zplatform.trade.service.ITxnsRefundService;
import com.zlebank.zplatform.trade.service.TradeNotifyService;
import com.zlebank.zplatform.trade.service.impl.InsteadPayNotifyTask;
import com.zlebank.zplatform.trade.service.impl.TxnsRefundServiceImpl;
import com.zlebank.zplatform.trade.utils.ConsUtil;
import com.zlebank.zplatform.trade.utils.ObjectDynamic;
import com.zlebank.zplatform.trade.utils.OrderNumber;
import com.zlebank.zplatform.trade.utils.SynHttpRequestThread;
import com.zlebank.zplatform.trade.utils.UUIDUtil;
import com.zlebank.zplatform.wechat.enums.ResultCodeEnum;
import com.zlebank.zplatform.wechat.enums.TradeStateCodeEnum;
import com.zlebank.zplatform.wechat.enums.WeChatRefundStateEnum;
import com.zlebank.zplatform.wechat.exception.WXVerifySignFailedException;
import com.zlebank.zplatform.wechat.service.WeChatService;
import com.zlebank.zplatform.wechat.wx.PrintBean;
import com.zlebank.zplatform.wechat.wx.WXApplication;
import com.zlebank.zplatform.wechat.wx.bean.PayResultBean;
import com.zlebank.zplatform.wechat.wx.bean.QueryBillBean;
import com.zlebank.zplatform.wechat.wx.bean.QueryOrderBean;
import com.zlebank.zplatform.wechat.wx.bean.QueryOrderResultBean;
import com.zlebank.zplatform.wechat.wx.bean.QueryRefundBean;
import com.zlebank.zplatform.wechat.wx.bean.QueryRefundResultBean;
import com.zlebank.zplatform.wechat.wx.bean.WXOrderBean;
import com.zlebank.zplatform.wechat.wx.common.WXConfigure;

/**
 * Class Description
 *
 * @author guojia
 * @version
 * @date 2016年5月25日 下午1:56:44
 * @since 
 */
@Service("weChatService")
public class WeChatServiceImpl implements WeChatService{
	private static final Log log = LogFactory.getLog(WeChatServiceImpl.class);
    
	@Autowired
	private ITxnsOrderinfoDAO txnsOrderinfoDAO; 
	@Autowired
	private ITxnsLogService txnsLogService;
	@Autowired
	private CoopInstiService coopInstiService;
	@Autowired
	private MerchMKService merchMKService;
	@Autowired
	private AccEntryService accEntryService;
	@Autowired
	private ITxnsRefundService txnsRefundService;
	@Autowired
	private RspmsgDAO rspmsgDAO;
	@Autowired
	private TradeNotifyService tradeNotifyService;
	
	/**
	 *
	 * @param tn
	 * @return
	 */
	@Override
	@Transactional(propagation=Propagation.REQUIRED,rollbackFor=Throwable.class)
	public JSONObject creatOrder(String tn) {
		//获取交易订单信息
		TxnsOrderinfoModel order = txnsOrderinfoDAO.getOrderByTN(tn);
		//获取交易流水数据
		TxnsLogModel txnsLog = txnsLogService.getTxnsLogByTxnseqno(order.getRelatetradetxn());
		//更新支付方信息
		PayPartyBean payPartyBean = new PayPartyBean(txnsLog.getTxnseqno(), 
				"05", 
				OrderNumber.getInstance().generateWeChatOrderNO(),//payordno,
				ChannelEnmu.WEBCHAT.getChnlcode(), 
				WXConfigure.getMchid(), 
				"", 
				DateUtil.getCurrentDateTime(), 
				"", 
				"");
		txnsLogService.updatePayInfo_Fast(payPartyBean);		//创建微信订单并提交
		WXApplication instance = new WXApplication();
		WXOrderBean order_wechat = new WXOrderBean();
		order_wechat.setBody(StringUtil.isEmpty(order.getGoodsname())?"默认商品":order.getGoodsname());
		order_wechat.setDetail(StringUtil.isEmpty(order.getOrderdesc())?"证联支付默认订单":order.getOrderdesc());
		order_wechat.setAttach("证联");
		order_wechat.setOut_trade_no(payPartyBean.getPayordno());
		order_wechat.setTotal_fee(txnsLog.getAmount()+"");
		order_wechat.setTime_start(DateUtil.getCurrentDateTime());
		order_wechat.setTime_expire(DateUtil.formatDateTime("yyyyMMddHHmmss",
				DateUtil.skipDateTime(new Date(), 1)));
		order_wechat.setGoods_tag("WXG");
		order_wechat.setNotify_url(ConsUtil.getInstance().cons.getWechat_notify_url());
		// 下订单
		//String payrettsnseqno = null;
		JSONObject json = null;
		try {
			json = instance.createOrder(order_wechat);
			//payrettsnseqno = json.get("prepayid")+"";
			//txnsLog = txnsLogService.getTxnsLogByTxnseqno(order.getRelatetradetxn());
			//txnsLog.setPayrettsnseqno(payrettsnseqno);
			//txnsLogService.update(txnsLog);
			txnsLogService.updateTradeStatFlag(txnsLog.getTxnseqno(), TradeStatFlagEnum.PAYING);
		} catch (WXVerifySignFailedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return json;
	}
	
	
	@Transactional(propagation=Propagation.REQUIRED,rollbackFor=Throwable.class)
	public void asyncTradeResult(PayResultBean result){
		//支付订单号
		String payOrderNo = result.getOut_trade_no();
		//交易结果
		ResultCodeEnum resultCodeEnum = ResultCodeEnum.fromValue(result.getResult_code());
		//交易流水数据
		TxnsLogModel txnsLog = txnsLogService.getTxnsLogByPayOrderNo(payOrderNo);
		//交易订单数据
		//TxnsOrderinfoModel order = txnsOrderinfoDAO.getOrderByTxnseqno(txnsLog.getTxnseqno());
		
		PayPartyBean payPartyBean = new PayPartyBean();
		payPartyBean.setTxnseqno(txnsLog.getTxnseqno());
		payPartyBean.setChnlTypeEnum(ChnlTypeEnum.WECHAT);
		if(resultCodeEnum==ResultCodeEnum.SUCCESS){//交易成功
			//txnsLog.setPayrettsnseqno(result.getTransaction_id());
			payPartyBean.setPayrettsnseqno(result.getTransaction_id());
			//txnsLog.setPayretcode(resultCodeEnum.getCode());
			payPartyBean.setPayretcode(resultCodeEnum.getCode());
			//txnsLog.setPayretinfo("交易成功");
			payPartyBean.setPayretinfo("交易成功");
			/*txnsLog.setTradestatflag("00000001");//交易完成结束位
		    txnsLog.setTradetxnflag("10000000");
		    txnsLog.setRelate("10000000");
		    txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
		    txnsLog.setTradeseltxn(UUIDUtil.uuid());
		    txnsLog.setRetcode("0000");
		    txnsLog.setRetinfo("交易成功");*/
		    txnsOrderinfoDAO.updateOrderToSuccess(txnsLog.getTxnseqno());
		    txnsLogService.updateWeChatTradeData(payPartyBean);
		}else if(resultCodeEnum==ResultCodeEnum.FAIL){//交易失败
			//txnsLog.setPayretcode(result.getErr_code());
			//txnsLog.setPayretinfo(result.getErr_code_des());
			payPartyBean.setPayretcode(result.getErr_code());
			payPartyBean.setPayretinfo(result.getErr_code_des());
			txnsOrderinfoDAO.updateOrderToFail(txnsLog.getTxnseqno());
			txnsLogService.updateTradeFailed(payPartyBean);
		}
		//txnsLog.setPayordfintime(DateUtil.getCurrentDateTime());
        //txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
		//更新支付方信息
		//txnsLogService.updateTxnsLog(txnsLog);
        
		
		if(resultCodeEnum==ResultCodeEnum.FAIL){
			return;
		}
		//处理账务
		/**账务处理开始 **/
        // 应用方信息
        try {
            AppPartyBean appParty = new AppPartyBean("",
                    "000000000000", DateUtil.getCurrentDateTime(),
                    DateUtil.getCurrentDateTime(), txnsLog.getTxnseqno(), "");
            txnsLogService.updateAppInfo(appParty);
            AccountingAdapterFactory.getInstance().getAccounting(BusiTypeEnum.fromValue(txnsLog.getBusitype())).accountedFor(txnsLog.getTxnseqno());
        } catch (Exception e) {
           log.error(e.getMessage());
           e.printStackTrace();
        }
        /**账务处理结束 **/
        
        tradeNotifyService.notify(txnsLog.getTxnseqno());
        
        /**异步通知处理结束 **/
        /*ResultBean orderResp = 
		        generateAsyncRespMessage(txnsLog.getTxnseqno());
		if (orderResp.isResultBool()) {
			if("000205".equals(order.getBiztype())){
        		AnonOrderAsynRespBean respBean = (AnonOrderAsynRespBean) orderResp.getResultObj();
        		
        		InsteadPayNotifyTask task = new InsteadPayNotifyTask();
        		//对匿名支付订单数据进行加密加签
        		responseData(respBean, txnsLog.getAccfirmerno(), txnsLog.getAccsecmerno(), task);
        		new SynHttpRequestThread(
                        StringUtil.isNotEmpty(order.getSecmemberno())?order.getSecmemberno():order.getFirmemberno(),
                        		order.getRelatetradetxn(),
                        		order.getBackurl(),
                        task).start();
        	}else{
        		OrderAsynRespBean respBean = (OrderAsynRespBean) orderResp
                        .getResultObj();
                new SynHttpRequestThread(
                		StringUtil.isNotEmpty(order.getSecmemberno())?order.getSecmemberno():order.getFirmemberno(),
                				order.getRelatetradetxn(),
                				order.getBackurl(),
                        respBean.getNotifyParam()).start();
        	}
		   
		}*/
      
       
	}

	
	

	
	/**
	 *
	 * @param queryBillBean
	 * @return
	 */
	@Override
	public List<String[]> dowanWeChatBill(QueryBillBean queryBillBean) {
		WXApplication ap = new WXApplication();
        PrintBean printBean = ap.downLoadBill(queryBillBean);
        if(printBean==null){
        	return null;
        }
		return printBean.getContent();
	}


	@Override
	@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Throwable.class)
	public void dealRefundBatch() {
	    log.info("查询退款定时任务开始【dealRefundBatch】");
		//1.查询待处理的退款的订单
		List<Map<String, Object>> txnlogs= (List<Map<String, Object>>) this.txnsLogService.getRefundOrderInfo(null,20);
		//JSONArray jsonArray = JSONArray.fromObject(txnlogs);
		for(Map<String, Object> txnslogMap : txnlogs){
			String txnseqno = txnslogMap.get("TXNSEQNO")+"";
			TxnsLogModel  txnsLog=this.txnsLogService.getTxnsLogByTxnseqno(txnseqno);
			WXApplication instance = new WXApplication();
			QueryRefundBean rb = new QueryRefundBean();
			rb.setOut_refund_no(txnsLog.getPayordno());
			log.info("调微信【查询退款】入参："+rb.toString());
			QueryRefundResultBean refund = instance.refundQuery(rb);
			log.info("调微信【查询退款】出参："+(null==refund?"无返回值":refund.getReturn_code().toString()));
			PayPartyBean payPartyBean = new PayPartyBean();
			payPartyBean.setTxnseqno(txnsLog.getTxnseqno());
			payPartyBean.setChnlTypeEnum(ChnlTypeEnum.WECHAT);
			if(Long.valueOf(refund.getRefund_count())==1){//现在的退款只可能有一笔
				WeChatRefundStateEnum refundStateEnum = WeChatRefundStateEnum.fromValue(refund.getRefundSub().get(0).getRefund_status());
				if(WeChatRefundStateEnum.SUCCESS==refundStateEnum){//交易成功
					payPartyBean.setPayrettsnseqno(txnsLog.getPayrettsnseqno());
					payPartyBean.setPayretcode(ResultCodeEnum.SUCCESS.getCode());
					payPartyBean.setPayretinfo("交易成功");
					txnsLogService.updateWeChatTradeData(payPartyBean);
				    //订单状态为成功
					txnsOrderinfoDAO.updateOrderToSuccess(txnseqno);
		            //退款成功
		            txnsRefundService.updateToSuccess(txnseqno);
				}else if(WeChatRefundStateEnum.FAIL==refundStateEnum){
					payPartyBean.setPayretcode(refund.getErr_code());
					payPartyBean.setPayretinfo(refund.getErr_code_des());
					txnsLogService.updateTradeFailed(payPartyBean);
					//订单状态为失败
					txnsOrderinfoDAO.updateOrderToFail(txnseqno);
					txnsRefundService.updateToFailed(txnseqno);
					log.info("退款跑批:"+txnseqno+"退款失败");
				}else if(WeChatRefundStateEnum.NOTSURE==refundStateEnum){
					txnsLog.setPayretcode(refund.getErr_code());
					txnsLog.setPayretinfo(refund.getErr_code_des());
					try {
						PojoRspmsg rspmsg = rspmsgDAO.getRspmsgByChnlCode(ChnlTypeEnum.WECHAT, refund.getErr_code());
						txnsLog.setRetcode(rspmsg.getWebrspcode());
					    txnsLog.setRetinfo(rspmsg.getRspinfo());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						txnsLog.setRetcode("3499");
					    txnsLog.setRetinfo("交易失败");
					}
					//订单状态为失败
					txnsOrderinfoDAO.updateOrderToFail(txnseqno);
					txnsRefundService.updateToFailed(txnseqno);
					log.info("退款跑批:"+txnseqno+"需商户重新发起");
				}
				
				 try {
		        	 AppPartyBean appParty = new AppPartyBean("",
		                     "000000000000", DateUtil.getCurrentDateTime(),
		                     DateUtil.getCurrentDateTime(), txnsLog.getTxnseqno(), "");
		        	txnsLogService.updateAppInfo(appParty);
		            AccountingAdapterFactory.getInstance().getAccounting(BusiTypeEnum.fromValue(txnsLog.getBusitype())).accountedFor(txnseqno);
		        } catch (Exception e) {
		            e.printStackTrace();
		        }
		        /**账务处理结束 **/
				log.info("查询退款定时任务结束【dealRefundBatch】 ");
			
			}
			
		}
		
		/*if(txnlogs.size()>0){
			 for (int i = 0; i < txnlogs.size(); i++) {
				 //JSONObject entity = jsonArray.getJSONObject(i);
				String txnseqno = txnlogs.get(i).get("TXNSEQNO")+"";
				TxnsLogModel  txnsLog=this.txnsLogService.getTxnsLogByTxnseqno(txnseqno);
				//TxnsOrderinfoModel order = txnsOrderinfoDAO.getOrderByTxnseqno(txnseqno);
				//原始交易流水
				//TxnsLogModel txnsLog_old = txnsLogService.getTxnsLogByTxnseqno(txnsLog.getTxnseqnoOg());
				//2.调微信服务端
				WXApplication instance = new WXApplication();
				QueryRefundBean rb = new QueryRefundBean();
				//商户订单号
				//rb.setOut_trade_no(txnsLog_old.getPayordno());
				//rb.setTransaction_id(txnsLog_old.getPayrettsnseqno());
				rb.setOut_refund_no(txnsLog.getPayordno());
				log.info("调微信【查询退款】入参："+rb.toString());
				QueryRefundResultBean refund = instance.refundQuery(rb);
				log.info("调微信【查询退款】出参："+(null==refund?"无返回值":refund.getReturn_code().toString()));
				
				PayPartyBean payPartyBean = new PayPartyBean();
				payPartyBean.setTxnseqno(txnsLog.getTxnseqno());
				payPartyBean.setChnlTypeEnum(ChnlTypeEnum.WECHAT);
				
				//3.根据返回结果处理
				if(ResultCodeEnum.SUCCESS.getCode().equals(refund.getRefundSub().get(0).getRefund_status())){
					//成功
					txnsLog.setPayretcode(ResultCodeEnum.SUCCESS.getCode());
					txnsLog.setPayretinfo("交易成功");
					txnsLog.setTradestatflag("00000001");//交易完成结束位
				    txnsLog.setTradetxnflag("10000000");
				    txnsLog.setRelate("10000000");
				    txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
				    txnsLog.setTradeseltxn(UUIDUtil.uuid());
				    txnsLog.setRetcode("0000");
				    txnsLog.setRetinfo("交易成功");
					
					payPartyBean.setPayrettsnseqno(txnsLog.getPayrettsnseqno());
					//txnsLog.setPayretcode(resultCodeEnum.getCode());
					payPartyBean.setPayretcode(ResultCodeEnum.SUCCESS.getCode());
					//txnsLog.setPayretinfo("交易成功");
					payPartyBean.setPayretinfo("交易成功");
				    
					txnsLogService.updateWeChatTradeData(payPartyBean);
				    //订单状态为成功
					//order.setStatus(OrderStatusEnum.SUCCESS.getStatus());
					txnsOrderinfoDAO.updateOrderToSuccess(txnseqno);
		            //退款成功
		            TxnsRefundModel refundEn = txnsRefundService.getRefundByTxnseqno(txnseqno);
		            refundEn.setStatus("00");
		            txnsRefundService.updateRefund(refundEn);
		            txnsRefundService.updateToSuccess(txnseqno);
				//3.2如果失败
				}else if(ResultCodeEnum.FAIL.getCode().equals(refund.getRefundSub().get(0).getRefund_status())){
					txnsLog.setPayretcode(refund.getErr_code());
					txnsLog.setPayretinfo(refund.getErr_code_des());
					//
					try {
						PojoRspmsg rspmsg = rspmsgDAO.getRspmsgByChnlCode(ChnlTypeEnum.WECHAT, refund.getErr_code());
						txnsLog.setRetcode(rspmsg.getWebrspcode());
					    txnsLog.setRetinfo(rspmsg.getRspinfo());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						txnsLog.setRetcode("3499");
					    txnsLog.setRetinfo("交易失败");
					}
					payPartyBean.setPayretcode(refund.getErr_code());
					payPartyBean.setPayretinfo(refund.getErr_code_des());
					txnsLogService.updateTradeFailed(payPartyBean);
					//订单状态为失败
					//order.setStatus(OrderStatusEnum.FAILED.getStatus());
					txnsOrderinfoDAO.updateOrderToFail(txnseqno);
					txnsRefundService.updateToFailed(txnseqno);
					log.info("退款跑批:"+txnseqno+"退款失败");
				//3.3需重新发起
				}else if(ResultCodeEnum.NOTSURE.getCode().equals(refund.getRefundSub().get(0).getRefund_status())){
					txnsLog.setPayretcode(refund.getErr_code());
					txnsLog.setPayretinfo(refund.getErr_code_des());
					try {
						PojoRspmsg rspmsg = rspmsgDAO.getRspmsgByChnlCode(ChnlTypeEnum.WECHAT, refund.getErr_code());
						txnsLog.setRetcode(rspmsg.getWebrspcode());
					    txnsLog.setRetinfo(rspmsg.getRspinfo());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						txnsLog.setRetcode("3499");
					    txnsLog.setRetinfo("交易失败");
					}
					//订单状态为失败
					//order.setStatus(OrderStatusEnum.FAILED.getStatus());
					txnsOrderinfoDAO.updateOrderToFail(txnseqno);
					txnsRefundService.updateToFailed(txnseqno);
					log.info("退款跑批:"+txnseqno+"需商户重新发起");
			    //3.4 //订单处理中不处理
				}
				//txnsLog.setPayordfintime(DateUtil.getCurrentDateTime());
		        //txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
				//更新支付方信息
				//txnsLogService.updateTxnsLog(txnsLog);
				//更新交易订单信息
				//txnsOrderinfoDAO.updateOrderinfo(order);
		        //更新订单状态
				//txnsOrderinfoDAO.update(order);
				 *//**账务处理开始 **//*
		        // 应用方信息
		        try {
		        	 AppPartyBean appParty = new AppPartyBean("",
		                     "000000000000", DateUtil.getCurrentDateTime(),
		                     DateUtil.getCurrentDateTime(), txnsLog.getTxnseqno(), "");
		        	txnsLogService.updateAppInfo(appParty);
		            AccountingAdapterFactory.getInstance().getAccounting(BusiTypeEnum.fromValue(txnsLog.getBusitype())).accountedFor(txnseqno);
		        } catch (Exception e) {
		            e.printStackTrace();
		        }
		        *//**账务处理结束 **//*
				log.info("查询退款定时任务结束【dealRefundBatch】 ");
				
		      }
	
		}*/
		
	}


	

	@Override
	@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Throwable.class)
	public ResultBean queryWechatOrder(TradeBean trade) {
		ResultBean resultBean = null;
		//1.查询订单的状态，如果是待支付，消费订单，并且已生成微信预订单号
		//当交易流水为空
		if(StringUtil.isEmpty(trade.getTn())){
			resultBean=new ResultBean("", "交易流水号【tn】不能为空！");
			return resultBean;
		}
		//获取交易订单信息
		TxnsOrderinfoModel order = txnsOrderinfoDAO.getOrderByTN(trade.getTn());
		if(order==null){
			resultBean=new ResultBean("", "请检查交易流水号【tn】！");
			return resultBean;
		}
		//获取交易流水数据
		TxnsLogModel txnsLog = txnsLogService.getTxnsLogByTxnseqno(order.getRelatetradetxn());
		if(txnsLog ==null){
			resultBean=new ResultBean("", "请检查交易流水号【tn】！");
			return resultBean;
		}
		
		//订单状态为成功或失败
		Boolean isStatus = order.getStatus().equals(OrderStatusEnum.SUCCESS.getStatus())
				||order.getStatus().equals(OrderStatusEnum.FAILED.getStatus())
				||order.getStatus().equals(OrderStatusEnum.INVALID.getStatus());
		//成功或失败，支付返回
		if(isStatus){
			resultBean = new ResultBean(order);
			return resultBean;
		}
		//是否微信渠道
		Boolean isChnl = txnsLog.getPayinst().equals(ChannelEnmu.WEBCHAT.getChnlcode());
		//是否微信类型
		Boolean isChnlType = txnsLog.getPaytype().equals("05");
		//是否为消费类型
		Boolean isBusiType = txnsLog.getBusitype().equals(BusiTypeEnum.consumption.getCode());
		if(isChnl&&isChnlType &&isBusiType){
			//2.需要调微信查询订单 调微信服务端
			WXApplication instance = new WXApplication();
			QueryOrderBean rb = new QueryOrderBean();
			//商户订单号
			rb.setOut_trade_no(txnsLog.getPayordno());
			rb.setTransaction_id(txnsLog.getPayrettsnseqno());
			log.info("调微信【查询订单状态】入参："+rb.toString());
			QueryOrderResultBean result = instance.queryOrder(rb);
			log.info("调微信【查询订单状态】出参："+(null==result?"无返回值":result.getReturn_code().toString()));	
			//3.通信成功
			if(ResultCodeEnum.SUCCESS.getCode().equals(result.getReturn_code())){
			     //返回状态为：支付成功，或 退款中
				if(result.getTrade_state().equals(TradeStateCodeEnum.SUCCESS.getCode())
				  ||result.getTrade_state().equals(TradeStateCodeEnum.REFUND.getCode())){
					txnsLog.setPayrettsnseqno(result.getTransaction_id()+"");
					txnsLog.setPayretcode(TradeStateCodeEnum.SUCCESS.getCode());
					txnsLog.setPayretinfo("交易成功");
					txnsLog.setTradestatflag("00000001");//交易完成结束位
				    txnsLog.setTradetxnflag("10000000");
				    txnsLog.setRelate("10000000");
				    txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
				    txnsLog.setTradeseltxn(UUIDUtil.uuid());
				    txnsLog.setRetcode("0000");
				    txnsLog.setRetinfo("交易成功");
					order.setStatus(OrderStatusEnum.SUCCESS.getStatus());
				//返回状态为：支付失败，或 已撤消	
			     }else if(result.getTrade_state().equals(TradeStateCodeEnum.PAYERROR.getCode())
			    		 ||result.getTrade_state().equals(TradeStateCodeEnum.REVOKED.getCode())){
			    	txnsLog.setPayretcode(result.getTrade_state());
					txnsLog.setPayretinfo(result.getTrade_state_desc());
					order.setStatus(OrderStatusEnum.FAILED.getStatus());
			     //返回状态为：支付中 
			     }else if(result.getTrade_state().equals(TradeStateCodeEnum.USERPAYING.getCode())){
			    	 order.setStatus(OrderStatusEnum.PAYING.getStatus());
			     }
			//3.2无业务报文
			}else if(ResultCodeEnum.FAIL.getCode().equals(result.getReturn_code())){
				resultBean = new ResultBean(result.getErr_code(), result.getErr_code_des());
				return resultBean;
			}
			txnsLog.setPayordfintime(DateUtil.getCurrentDateTime());
	        txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
			//更新支付方信息
			txnsLogService.updateTxnsLog(txnsLog);
			//更新交易订单信息
			txnsOrderinfoDAO.updateOrderinfo(order);
			if((result.getTrade_state().equals(TradeStateCodeEnum.SUCCESS.getCode())
		    		 ||result.getTrade_state().equals(TradeStateCodeEnum.REFUND.getCode()))){
				//处理账务
				/**账务处理开始 **/
		        // 应用方信息
		        try {
		            AppPartyBean appParty = new AppPartyBean("",
		                    "000000000000", DateUtil.getCurrentDateTime(),
		                    DateUtil.getCurrentDateTime(), txnsLog.getTxnseqno(), "AC000000");
		            txnsLogService.updateAppInfo(appParty);
		            IAccounting accounting = AccountingAdapterFactory.getInstance().getAccounting(BusiTypeEnum.fromValue(txnsLog.getBusitype()));
		            ResultBean accountResultBean = accounting.accountedFor(txnsLog.getTxnseqno());
		            txnsLogService.updateAppStatus(txnsLog.getTxnseqno(), accountResultBean.getErrCode(), accountResultBean.getErrMsg());
		            
		        } catch (Exception e) {
		            log.error(e.getMessage());
		            e.printStackTrace();
		            resultBean = new ResultBean("", result.getErr_code_des());
					return resultBean;
		        }
		        /**账务处理结束 **/
				/**异步通知处理开始  **/
		        tradeNotifyService.notify(txnsLog.getTxnseqno());
		        /**异步通知处理结束 **/
			}
		}
		resultBean = new ResultBean(order);
		return resultBean;
	}


	@Override
	@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Throwable.class)
	public void dealAnsyOrder() {
		log.info("定时任务微信订单查询开始：dealAnsyOrder start");
		Map<String,Object> map= new HashMap<String,Object>();
		//订单状态
		List<String> statList=new ArrayList<String>();
		statList.add(OrderStatusEnum.INITIAL.getStatus());
		statList.add(OrderStatusEnum.PAYING.getStatus());
		map.put("statList",statList);
		//微信类型
		map.put("paytype", "05");
		//微信渠道
		map.put("painst", ChannelEnmu.WEBCHAT.getChnlcode());
		//消费类型
		map.put("busitype", BusiTypeEnum.consumption.getCode());
		List<Object> txnlogs = this.txnsLogService.queryTxnsLog(map);
		for(Object item : txnlogs){
			String txnseqno = item.toString();
			TxnsLogModel txnsLog= this.txnsLogService.getTxnsLogByTxnseqno(txnseqno); 
            TxnsOrderinfoModel order= this.txnsOrderinfoDAO.getOrderByTxnseqno(txnsLog.getTxnseqno());
			//2.需要调微信查询订单 调微信服务端
			WXApplication instance = new WXApplication();
			QueryOrderBean rb = new QueryOrderBean();
			//商户订单号
			rb.setOut_trade_no(txnsLog.getPayordno());
			rb.setTransaction_id(txnsLog.getPayrettsnseqno());
			log.info("调微信【查询订单状态】入参：out_trade_no="+rb.getOut_trade_no()+",transaction_id"+rb.getTransaction_id());
			QueryOrderResultBean result = instance.queryOrder(rb);
			log.info("调微信【查询订单状态】出参："+(null==result?"无返回值":result.getReturn_code().toString()));	
			//3.通信成功
			if(ResultCodeEnum.SUCCESS.getCode().equals(result.getReturn_code())){
				log.info("微信返回订单状态"+result.getTrade_state()+":"+result.getTrade_state_desc());
			     //返回状态为：支付成功，或 退款中
				if(result.getTrade_state().equals(TradeStateCodeEnum.SUCCESS.getCode())
				  ||result.getTrade_state().equals(TradeStateCodeEnum.REFUND.getCode())){
					txnsLog.setPayrettsnseqno(result.getTransaction_id()+"");
					txnsLog.setPayretcode(TradeStateCodeEnum.SUCCESS.getCode());
					txnsLog.setPayretinfo("交易成功");
					txnsLog.setTradestatflag("00000001");//交易完成结束位
				    txnsLog.setTradetxnflag("10000000");
				    txnsLog.setRelate("10000000");
				    txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
				    txnsLog.setTradeseltxn(UUIDUtil.uuid());
				    txnsLog.setRetcode("0000");
				    txnsLog.setRetinfo("交易成功");
					order.setStatus(OrderStatusEnum.SUCCESS.getStatus());
				//返回状态为：支付失败，或 已撤消	
			     }else if(result.getTrade_state().equals(TradeStateCodeEnum.PAYERROR.getCode())
			    		 ||result.getTrade_state().equals(TradeStateCodeEnum.REVOKED.getCode())){
			    	txnsLog.setPayretcode(result.getTrade_state());
					txnsLog.setPayretinfo(result.getTrade_state_desc());
					try {
						PojoRspmsg rspmsg = rspmsgDAO.getRspmsgByChnlCode(ChnlTypeEnum.WECHAT, result.getErr_code());
						txnsLog.setRetcode(rspmsg.getWebrspcode());
					    txnsLog.setRetinfo(rspmsg.getRspinfo());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						txnsLog.setRetcode("3499");
					    txnsLog.setRetinfo("交易失败");
					}
					order.setStatus(OrderStatusEnum.FAILED.getStatus());
			     //返回状态为：支付中 
			     }else if(result.getTrade_state().equals(TradeStateCodeEnum.USERPAYING.getCode())){
			    	 order.setStatus(OrderStatusEnum.PAYING.getStatus());
			     }
			//3.2无业务报文
			}else if(ResultCodeEnum.FAIL.getCode().equals(result.getReturn_code())){
				log.error("微信订单查询报错:"+result.getReturn_code()+result.getReturn_msg());
				continue;
			}
			txnsLog.setPayordfintime(DateUtil.getCurrentDateTime());
	        txnsLog.setRetdatetime(DateUtil.getCurrentDateTime());
			//更新支付方信息
			txnsLogService.updateTxnsLog(txnsLog);
			//更新交易订单信息
			txnsOrderinfoDAO.updateOrderinfo(order);
			if((result.getTrade_state().equals(TradeStateCodeEnum.SUCCESS.getCode())
		    		 ||result.getTrade_state().equals(TradeStateCodeEnum.REFUND.getCode()))){
				//处理账务
				/**账务处理开始 **/
		        // 应用方信息
		        try {
		            AppPartyBean appParty = new AppPartyBean("",
		                    "000000000000", DateUtil.getCurrentDateTime(),
		                    DateUtil.getCurrentDateTime(), txnsLog.getTxnseqno(), "AC000000");
		            txnsLogService.updateAppInfo(appParty);
		            IAccounting accounting = AccountingAdapterFactory.getInstance().getAccounting(BusiTypeEnum.fromValue(txnsLog.getBusitype()));
		            ResultBean accountResultBean = accounting.accountedFor(txnsLog.getTxnseqno());
		            txnsLogService.updateAppStatus(txnsLog.getTxnseqno(), accountResultBean.getErrCode(), accountResultBean.getErrMsg());
		        } catch (Exception e) {
		            log.error(e.getMessage());
		            break;
		        }
		        /**账务处理结束 **/
				/**异步通知处理开始  **/
		        tradeNotifyService.notify(txnseqno);
		        /**异步通知处理结束 **/
			}
				
			}
		
		log.info("定时任务微信订单查询结束：dealAnsyOrder end");
		
	}
	
	
	public ResultBean queryOrder(String txnseqno){
		TxnsLogModel txnsLog = txnsLogService.getTxnsLogByTxnseqno(txnseqno);
		BusiTypeEnum busiTypeEnum = BusiTypeEnum.fromValue(txnsLog.getBusitype());
		ResultBean resultBean = null;
		if(busiTypeEnum==BusiTypeEnum.consumption||busiTypeEnum==BusiTypeEnum.charge){
			WXApplication instance = new WXApplication();
			QueryOrderBean rb = new QueryOrderBean();
			//商户订单号
			rb.setOut_trade_no(txnsLog.getPayordno());
			//rb.setTransaction_id(txnsLog.getPayrettsnseqno());
			log.info("调微信【查询订单状态】入参：out_trade_no="+rb.getOut_trade_no()+",transaction_id"+rb.getTransaction_id());
			QueryOrderResultBean result = instance.queryOrder(rb);
			log.info("调微信【查询订单状态】出参："+(null==result?"无返回值":result.getReturn_code().toString()));	
			resultBean = new ResultBean(result);
			resultBean.setErrCode(result.getTrade_state());
		}else if(busiTypeEnum==BusiTypeEnum.refund){
			TxnsLogModel txnsLog_old = txnsLogService.getTxnsLogByTxnseqno(txnsLog.getTxnseqnoOg());
			WXApplication instance = new WXApplication();
			QueryRefundBean rb = new QueryRefundBean();
			//商户订单号
			rb.setOut_trade_no(txnsLog_old.getPayordno());
			rb.setTransaction_id(txnsLog_old.getPayrettsnseqno());
			log.info("调微信【查询订单状态】入参："+rb.toString());
			QueryRefundResultBean result = instance.refundQuery(rb);
			log.info("调微信【查询订单状态】出参："+(null==result?"无返回值":result.getReturn_code().toString()));	
			resultBean = new ResultBean(result);
			resultBean.setErrCode(result.getRefundSub().get(0).getRefund_status());
		}
		return resultBean;
	}
	
	public ResultBean dealWithAccounting(String txnseqno,ResultBean resultBean){
		TxnsLogModel txnsLog = txnsLogService.getTxnsLogByTxnseqno(txnseqno);
		BusiTypeEnum busiTypeEnum = BusiTypeEnum.fromValue(txnsLog.getBusitype());
		//充值或者消费交易
		if(busiTypeEnum==BusiTypeEnum.consumption||busiTypeEnum==BusiTypeEnum.charge){
			QueryOrderResultBean result = (QueryOrderResultBean) resultBean.getResultObj();
			ResultCodeEnum resultCodeEnum = ResultCodeEnum.fromValue(result.getReturn_code());
			if(ResultCodeEnum.SUCCESS==resultCodeEnum){
				PayPartyBean payPartyBean =  new PayPartyBean();
				payPartyBean.setTxnseqno(txnsLog.getTxnseqno());
				payPartyBean.setChnlTypeEnum(ChnlTypeEnum.WECHAT);
				payPartyBean.setPayinst(ChannelEnmu.WEBCHAT.getChnlcode());
				TradeStateCodeEnum tradeStateCodeEnum = TradeStateCodeEnum.fromValue(result.getTrade_state());
			     //返回状态为：支付成功，或 退款中
				if(tradeStateCodeEnum==TradeStateCodeEnum.SUCCESS){
					payPartyBean.setPayrettsnseqno(result.getTransaction_id());
					payPartyBean.setPayretcode(resultCodeEnum.getCode());
					payPartyBean.setPayretinfo("交易成功");
				    txnsOrderinfoDAO.updateOrderToSuccess(txnseqno);
				    //更新微信核心交易支付方应答信息和核心交易信息
				    txnsLogService.updateWeChatTradeData(payPartyBean);
				    
				    try {
			            AppPartyBean appParty = new AppPartyBean("",
			                    "000000000000", DateUtil.getCurrentDateTime(),
			                    DateUtil.getCurrentDateTime(), txnsLog.getTxnseqno(), "");
			            txnsLogService.updateAppInfo(appParty);
			            AccountingAdapterFactory.getInstance().getAccounting(BusiTypeEnum.fromValue(txnsLog.getBusitype())).accountedFor(txnsLog.getTxnseqno());
			        } catch (Exception e) {
			            log.error(e.getMessage());
			            e.printStackTrace();
			            resultBean = new ResultBean("", result.getErr_code_des());
						return resultBean;
			        }
				//返回状态为：支付失败，或 已撤消	
			    }else if(tradeStateCodeEnum==TradeStateCodeEnum.PAYERROR||
			    		  tradeStateCodeEnum==TradeStateCodeEnum.REVOKED||
			    		  tradeStateCodeEnum==TradeStateCodeEnum.CLOSED){
			    	
			    	payPartyBean.setPayretcode(result.getErr_code());
			    	payPartyBean.setPayretinfo(result.getErr_code_des());
					txnsOrderinfoDAO.updateOrderToFail(txnseqno);
					txnsLogService.updateTradeFailed(payPartyBean);
			    }else if(tradeStateCodeEnum==TradeStateCodeEnum.NOTPAY){//交易未支付
			    	 
			    	return null;
			    }else if(result.getTrade_state().equals(TradeStateCodeEnum.USERPAYING.getCode())){//返回状态为：支付中 
			    	return null;
			    }
				//交易成功或者交易失败均有异步通知
				tradeNotifyService.notify(txnsLog.getTxnseqno());
			//
			}else if(ResultCodeEnum.FAIL==resultCodeEnum){//业务报文失败，没有返回业务报文
				resultBean = new ResultBean(result.getErr_code(), result.getErr_code_des());
				return resultBean;
			}
		}else if(busiTypeEnum==BusiTypeEnum.refund){
			QueryRefundResultBean refund = (QueryRefundResultBean) resultBean.getResultObj();
			PayPartyBean payPartyBean = new PayPartyBean();
			payPartyBean.setTxnseqno(txnsLog.getTxnseqno());
			payPartyBean.setChnlTypeEnum(ChnlTypeEnum.WECHAT);
			payPartyBean.setPayinst(ChannelEnmu.WEBCHAT.getChnlcode());
			
			if(Long.valueOf(refund.getRefund_count())==1){//现在的退款只可能有一笔
				WeChatRefundStateEnum refundStateEnum = WeChatRefundStateEnum.fromValue(refund.getRefundSub().get(0).getRefund_status());
				
				if(WeChatRefundStateEnum.SUCCESS==refundStateEnum){
					//成功
					payPartyBean.setPayrettsnseqno(txnsLog.getPayrettsnseqno());
					payPartyBean.setPayretcode(ResultCodeEnum.SUCCESS.getCode());
					payPartyBean.setPayretinfo("交易成功");
					txnsLogService.updateWeChatTradeData(payPartyBean);
				    //订单状态为成功
					txnsOrderinfoDAO.updateOrderToSuccess(txnseqno);
		            //退款成功
				//3.2如果失败
				}else if(WeChatRefundStateEnum.FAIL==refundStateEnum){
					
					payPartyBean.setPayretcode(refund.getErr_code());
					payPartyBean.setPayretinfo(refund.getErr_code_des());
					txnsLogService.updateTradeFailed(payPartyBean);
					//订单状态为失败
					txnsOrderinfoDAO.updateOrderToFail(txnseqno);
					//退款失败
					log.info("退款跑批:"+txnseqno+"退款失败");
				//3.3需重新发起
				}else if(WeChatRefundStateEnum.NOTSURE==refundStateEnum){
					txnsLog.setPayretcode(refund.getErr_code());
					txnsLog.setPayretinfo(refund.getErr_code_des());
					try {
						PojoRspmsg rspmsg = rspmsgDAO.getRspmsgByChnlCode(ChnlTypeEnum.WECHAT, refund.getErr_code());
						txnsLog.setRetcode(rspmsg.getWebrspcode());
					    txnsLog.setRetinfo(rspmsg.getRspinfo());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						txnsLog.setRetcode("3499");
					    txnsLog.setRetinfo("交易失败");
					}
					//订单状态为失败
					txnsOrderinfoDAO.updateOrderToFail(txnseqno);
					log.info("退款跑批:"+txnseqno+"需商户重新发起");
				}
				//更新支付方信息
				//txnsLogService.updateTxnsLog(txnsLog);
				 /**账务处理开始 **/
		        // 应用方信息
		        try {
		        	 AppPartyBean appParty = new AppPartyBean("",
		                     "000000000000", DateUtil.getCurrentDateTime(),
		                     DateUtil.getCurrentDateTime(), txnsLog.getTxnseqno(), "");
		        	txnsLogService.updateAppInfo(appParty);
		            AccountingAdapterFactory.getInstance().getAccounting(BusiTypeEnum.fromValue(txnsLog.getBusitype())).accountedFor(txnseqno);
		        } catch (Exception e) {
		            e.printStackTrace();
		        }
			}
			
	        /**账务处理结束 **/
			log.info("查询退款定时任务结束【dealRefundBatch】 ");
		}
		return null;
	}
}
