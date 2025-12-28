package com.example.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.mall.entity.Orders;
import com.example.mall.entity.Product;
import com.example.mall.entity.User;
import com.example.mall.mapper.OrderMapper;
import com.example.mall.mapper.ProductMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

@Controller
public class MallController {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private JavaMailSender mailSender;

    // ================= [ 1. 顾客功能 - 首页与订单 ] =================

    // 首页：展示商品列表
    @GetMapping("/")
    public String index(Model model) {
        List<Product> products = productMapper.selectList(null);
        model.addAttribute("productList", products);
        return "index";
    }

    // 购买流程：处理购买请求并发送邮件
    @Transactional // 👈 新增：事务注解，确保“扣库存”和“下订单”要么同时成功，要么同时失败
    @PostMapping("/buy")
    public String buy(@RequestParam Long productId, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        // 1. 获取商品最新信息
        Product product = productMapper.selectById(productId);
        if (product == null) {
            return "redirect:/?error=notfound";
        }

        // 2. 【新增逻辑】检查并扣减库存
        if (product.getStock() <= 0) {
            // 如果库存不足，跳回首页并带上错误提示
            return "redirect:/?error=no_stock";
        }
        // 库存减 1
        product.setStock(product.getStock() - 1);
        // 更新数据库中的商品信息
        productMapper.updateById(product);

        // 3. 创建订单记录
        Orders order = new Orders();
        order.setUserId(user.getId());
        order.setProductId(productId);
        order.setTotalAmount(product.getPrice());
        order.setStatus("已发货");
        orderMapper.insert(order);

        // 4. 发送发货确认邮件
        try {
            sendEmail(user.getEmail(), user.getUsername(), product.getName(), order.getId());
        } catch (Exception e) {
            System.err.println("邮件发送失败：" + e.getMessage());
        }

        return "redirect:/orders";
    }

    // 查看订单状态和历史
    @GetMapping("/orders")
    public String orderHistory(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        List<Orders> orders = orderMapper.selectList(
                new QueryWrapper<Orders>().eq("user_id", user.getId()).orderByDesc("id")
        );
        model.addAttribute("orderList", orders);
        return "orders";
    }

    // ================= [ 2. 销售管理 - 商品目录 CRUD ] =================

    // 展示商品管理列表
    @GetMapping("/admin/products")
    public String adminProductList(Model model) {
        model.addAttribute("productList", productMapper.selectList(null));
        return "admin_product_list";
    }

    // 添加商品 - 页面
    @GetMapping("/admin/product/add")
    public String addProductPage() {
        return "admin_product_add";
    }

    // 添加商品 - 提交
    @PostMapping("/admin/product/add")
    public String addProduct(Product product) {
        productMapper.insert(product);
        return "redirect:/admin/products";
    }

    // 修改商品 - 页面 (根据ID查询)
    @GetMapping("/admin/product/edit/{id}")
    public String editProductPage(@PathVariable Long id, Model model) {
        Product product = productMapper.selectById(id);
        model.addAttribute("product", product);
        return "admin_product_edit";
    }

    // 修改商品 - 提交
    @PostMapping("/admin/product/update")
    public String updateProduct(Product product) {
        productMapper.updateById(product);
        return "redirect:/admin/products";
    }

    // 删除商品
    @GetMapping("/admin/product/delete/{id}")
    public String deleteProduct(@PathVariable Long id) {
        productMapper.deleteById(id);
        return "redirect:/admin/products";
    }

    // ================= [ 3. 销售管理 - 统计报表 ] =================

    // 【新增这一段：负责跳转到报表网页】
    @GetMapping("/admin/stats")
    public String statsPage() {
        return "admin_stats"; // 必须对应 templates 文件夹下的 admin_stats.html
    }

    // 新增：给图表提供真实数据的接口
    @GetMapping("/api/admin/stats-data")
    @ResponseBody // 这个注解很重要，表示返回 JSON 数据而不是网页
    public List<Map<String, Object>> getRealStatsData() {
        // 使用 MyBatis-Plus 的原生查询，按日期分组统计销售额
        // 逻辑：查询日期(day) 和 当天总金额(total)
        return orderMapper.selectMaps(new QueryWrapper<Orders>()
                .select("DATE_FORMAT(create_time, '%m-%d') as day", "SUM(total_amount) as total")
                .groupBy("day")
                .last("LIMIT 7")); // 只查最近7天的
    }
    // ================= [ 辅助：邮件发送方法 ] =================

    private void sendEmail(String toEmail, String username, String productName, Long orderId) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("3497136392@qq.com");
        message.setTo(toEmail);
        message.setSubject("🚀 笑而不语的数码小店 - 订单发货确认通知");
        message.setText("尊敬的 " + username + "：\n\n" +
                "您购买的商品 [" + productName + "] 已确认发货！\n" +
                "订单编号：" + orderId + "\n" +
                "感谢您的支持，祝您生活愉快。");
        mailSender.send(message);
    }
}